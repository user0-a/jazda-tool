package com.example.branchmerger.service;

import com.example.branchmerger.config.GitProperties;
import com.example.branchmerger.conflict.ConflictResolver;
import com.example.branchmerger.dto.MergeResult;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitMergeService {

    private static final Logger log = LoggerFactory.getLogger(GitMergeService.class);

    private final GitProperties props;
    private final ConflictResolver conflictResolver;
    private final MigrationNormalizer migrationNormalizer;

    public GitMergeService(GitProperties props,
                           ConflictResolver conflictResolver,
                           MigrationNormalizer migrationNormalizer) {
        this.props = props;
        this.conflictResolver = conflictResolver;
        this.migrationNormalizer = migrationNormalizer;
    }

    /**
     * Fetches the remote, checks out {@code targetBranch}, merges the latest
     * {@code main} into it, and pushes the result.
     */
    public MergeResult mergeMainInto(String targetBranch) {
        return mergeMainInto(targetBranch, false);
    }

    /**
     * @param currentVersionUpgrade when true, rewrites the version marker line in the
     *        version file (default {@code pvt}) to main's version with its last segment
     *        incremented by one.
     */
    public MergeResult mergeMainInto(String targetBranch, boolean currentVersionUpgrade) {
        String remote = props.getRemote();
        String mainBranch = props.getMainBranch();
        CredentialsProvider cp = credentials();

        File repoDir = new File(props.getRepoPath());
        if (!new File(repoDir, ".git").exists()) {
            return MergeResult.of(MergeResult.Status.FAILED, targetBranch,
                    "No git repository found at " + repoDir.getAbsolutePath());
        }

        try (Git git = Git.open(repoDir)) {

            // 1. Get the latest refs from the remote.
            git.fetch()
                    .setRemote(remote)
                    .setCredentialsProvider(cp)
                    .call();

            // 2. Make sure the target branch exists remotely.
            String remoteTargetRef = remote + "/" + targetBranch;
            if (git.getRepository().resolve(remoteTargetRef) == null) {
                return MergeResult.of(MergeResult.Status.FAILED, targetBranch,
                        "Branch '" + targetBranch + "' does not exist on remote '" + remote + "'.");
            }

            // 3. Check out the target branch, creating a local tracking branch if needed,
            //    and hard-reset it to the remote so we start from a clean, up-to-date state.
            checkoutTracking(git, targetBranch, remote);
            git.reset().setMode(ResetType.HARD).setRef(remoteTargetRef).call();

            // 3b. Work out the migration renumbering BEFORE merging, while the branch
            //     still reflects the feature's own state. Applied after the merge.
            MigrationPlan plan = migrationNormalizer.computePlan(git, targetBranch);

            // 4. Merge origin/main into the target branch.
            ObjectId mainId = git.getRepository().resolve(remote + "/" + mainBranch);
            if (mainId == null) {
                return MergeResult.of(MergeResult.Status.FAILED, targetBranch,
                        "Could not resolve " + remote + "/" + mainBranch);
            }

            org.eclipse.jgit.api.MergeResult merge = git.merge()
                    .include(mainId)
                    .setStrategy(MergeStrategy.RECURSIVE)
                    .setCommit(true)
                    .setMessage("Merge " + remote + "/" + mainBranch + " into " + targetBranch)
                    .call();

            log.info("Merge status for {}: {}", targetBranch, merge.getMergeStatus());

            switch (merge.getMergeStatus()) {
                case ALREADY_UP_TO_DATE:
                    return MergeResult.of(MergeResult.Status.ALREADY_UP_TO_DATE, targetBranch,
                            "Branch already contains all of " + mainBranch + ".");

                case FAST_FORWARD:
                case MERGED:
                    normalizeMigrations(git, plan, targetBranch, currentVersionUpgrade);
                    push(git, targetBranch, remote, cp);
                    MergeResult ok = MergeResult.of(MergeResult.Status.MERGED_AND_PUSHED, targetBranch,
                            "Merged " + mainBranch + " into " + targetBranch
                                    + (plan.hasMigration() ? " (migration renumbered to "
                                        + plan.newNumber() + ")" : "") + " and pushed.");
                    org.eclipse.jgit.lib.ObjectId head =
                            git.getRepository().resolve("refs/heads/" + targetBranch);
                    if (head != null) {
                        ok.setMergeCommitId(head.getName());
                    }
                    return ok;

                case CONFLICTING:
                    return handleConflicts(git, targetBranch, remote, cp, merge, plan,
                            currentVersionUpgrade);

                default:
                    abort(git);
                    return MergeResult.of(MergeResult.Status.FAILED, targetBranch,
                            "Merge failed with status " + merge.getMergeStatus());
            }

        } catch (Exception e) {
            log.error("Merge of {} failed", targetBranch, e);
            return MergeResult.of(MergeResult.Status.FAILED, targetBranch,
                    "Error: " + e.getMessage());
        }
    }

    private MergeResult handleConflicts(Git git, String targetBranch, String remote,
                                        CredentialsProvider cp,
                                        org.eclipse.jgit.api.MergeResult merge,
                                        MigrationPlan plan,
                                        boolean currentVersionUpgrade) throws Exception {

        List<String> conflicting = new ArrayList<>();
        if (merge.getConflicts() != null) {
            conflicting.addAll(merge.getConflicts().keySet());
        }

        // Hand off to the content-aware resolver. A true return means every hunk
        // was resolved and staged, so we can commit the merge.
        boolean resolved = conflictResolver.resolve(git, merge);

        if (resolved) {
            git.commit()
                    .setMessage("Merge " + props.getRemote() + "/" + props.getMainBranch()
                            + " into " + targetBranch + " (conflicts auto-resolved)")
                    .call();
            normalizeMigrations(git, plan, targetBranch, currentVersionUpgrade);
            push(git, targetBranch, remote, cp);
            MergeResult r = MergeResult.of(MergeResult.Status.MERGED_AND_PUSHED, targetBranch,
                    "Conflicts auto-resolved, merged and pushed.");
            r.setConflictingFiles(conflicting);
            return r;
        }

        // Not resolved -> leave the branch clean.
        abort(git);
        MergeResult r = MergeResult.of(MergeResult.Status.CONFLICTS, targetBranch,
                "Merge produced conflicts; no resolution rules applied. Merge aborted.");
        r.setConflictingFiles(conflicting);
        return r;
    }

    /** Renumbers migrations, forces feature-owned files, optionally bumps the version; commits if changed. */
    private void normalizeMigrations(Git git, MigrationPlan plan, String targetBranch,
                                     boolean currentVersionUpgrade) throws Exception {
        boolean changed = migrationNormalizer.apply(git, plan);
        changed |= restoreFeatureFiles(git, targetBranch);
        if (currentVersionUpgrade) {
            changed |= upgradeCurrentVersion(git, targetBranch);
        }
        if (changed) {
            StringBuilder msg = new StringBuilder("Post-merge normalization after merging "
                    + props.getMainBranch() + " into " + targetBranch);
            if (plan.hasMigration()) {
                msg.append(" (migration renumbered to ").append(props.getMigrationPrefix())
                        .append(plan.newNumber()).append(")");
            }
            git.commit().setMessage(msg.toString()).call();
        }
    }

    /**
     * Rewrites the version marker line in the version file to main's version with its
     * last numeric segment incremented by one. E.g. main has "// currentVersion: 3.4.2313"
     * -> the feature file's marker becomes "// currentVersion: 3.4.2314". The rest of the
     * file (kept as the feature's version) is untouched.
     *
     * @return true if the file was changed and staged
     */
    private boolean upgradeCurrentVersion(Git git, String branch) throws Exception {
        Repository repo = git.getRepository();
        String versionFile = props.getVersionFile();

        // Main's current version.
        ObjectId mainId = repo.resolve(props.getRemote() + "/" + props.getMainBranch());
        if (mainId == null) {
            return false;
        }
        String mainContent;
        try (RevWalk walk = new RevWalk(repo)) {
            byte[] bytes = blobAt(repo, walk.parseCommit(mainId), versionFile);
            if (bytes == null) {
                return false;
            }
            mainContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        Matcher mainMatcher = versionLinePattern().matcher(mainContent);
        if (!mainMatcher.find()) {
            log.warn("currentVersionUpgrade requested but no version marker found in {} on {}",
                    versionFile, props.getMainBranch());
            return false;
        }
        String newVersion = incrementLastSegment(mainMatcher.group(2));

        // Rewrite the marker line in the feature file (already restored to the feature version).
        Path file = repo.getWorkTree().toPath().resolve(versionFile);
        if (!Files.exists(file)) {
            return false;
        }
        String featureContent = Files.readString(file);
        Matcher featureMatcher = versionLinePattern().matcher(featureContent);
        if (!featureMatcher.find()) {
            log.warn("No version marker in feature {} to upgrade", versionFile);
            return false;
        }
        String updated = featureMatcher.replaceFirst("$1" + Matcher.quoteReplacement(newVersion));
        if (updated.equals(featureContent)) {
            return false;
        }
        Files.writeString(file, updated);
        git.add().addFilepattern(versionFile).call();
        log.info("Upgraded version marker in {} to {}", versionFile, newVersion);
        return true;
    }

    /** Group 1 = marker prefix + whitespace, group 2 = version number. */
    private Pattern versionLinePattern() {
        return Pattern.compile("(" + Pattern.quote(props.getVersionMarkerPrefix())
                + "\\s*)([0-9]+(?:\\.[0-9]+)*)");
    }

    private String incrementLastSegment(String version) {
        String[] parts = version.split("\\.");
        try {
            long last = Long.parseLong(parts[parts.length - 1]);
            parts[parts.length - 1] = Long.toString(last + 1);
        } catch (NumberFormatException e) {
            return version; // last segment not numeric; leave as-is
        }
        return String.join(".", parts);
    }

    /**
     * Forces the configured feature-owned files (e.g. pvt) to the feature branch's
     * pre-merge content, so main can never overwrite them. origin/&lt;branch&gt; still
     * points at the feature tip throughout the local merge, so it is the source of truth.
     *
     * @return true if any file was changed and staged
     */
    private boolean restoreFeatureFiles(Git git, String branch) throws Exception {
        List<String> paths = props.getKeepOursPaths();
        if (paths == null || paths.isEmpty()) {
            return false;
        }
        Repository repo = git.getRepository();
        ObjectId featureTip = repo.resolve(props.getRemote() + "/" + branch);
        if (featureTip == null) {
            featureTip = repo.resolve(branch);
        }
        if (featureTip == null) {
            return false;
        }

        boolean changed = false;
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit tip = walk.parseCommit(featureTip);
            for (String path : paths) {
                byte[] featureContent = blobAt(repo, tip, path);
                Path workFile = repo.getWorkTree().toPath().resolve(path);
                boolean tracked = repo.readDirCache().findEntry(path) >= 0;

                if (featureContent == null) {
                    // Feature doesn't have the file -> keeping "feature version" means removing it.
                    if (tracked) {
                        git.rm().addFilepattern(path).call();
                        changed = true;
                    }
                    continue;
                }

                byte[] current = Files.exists(workFile) ? Files.readAllBytes(workFile) : null;
                if (current == null || !Arrays.equals(current, featureContent)) {
                    if (workFile.getParent() != null) {
                        Files.createDirectories(workFile.getParent());
                    }
                    Files.write(workFile, featureContent);
                    git.add().addFilepattern(path).call();
                    changed = true;
                    log.info("Kept feature-branch version of {}", path);
                }
            }
        }
        return changed;
    }

    private byte[] blobAt(Repository repo, RevCommit commit, String path) throws Exception {
        try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (tw == null) {
                return null;
            }
            return repo.open(tw.getObjectId(0)).getBytes();
        }
    }

    private void checkoutTracking(Git git, String branch, String remote) throws Exception {
        boolean localExists = git.branchList().call().stream()
                .map(Ref::getName)
                .anyMatch(name -> name.equals("refs/heads/" + branch));

        if (localExists) {
            git.checkout().setName(branch).call();
        } else {
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branch)
                    .setUpstreamMode(SetupUpstreamMode.TRACK)
                    .setStartPoint(remote + "/" + branch)
                    .call();
        }
    }

    private void push(Git git, String branch, String remote, CredentialsProvider cp) throws Exception {
        git.push()
                .setRemote(remote)
                .setCredentialsProvider(cp)
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                .call();
    }

    private void abort(Git git) {
        try {
            // Roll the working tree back to the pre-merge HEAD.
            git.reset().setMode(ResetType.HARD).setRef("HEAD").call();
        } catch (Exception e) {
            log.warn("Failed to abort/clean merge state", e);
        }
    }

    private CredentialsProvider credentials() {
        String user = props.getUsername() != null ? props.getUsername() : "x-access-token";
        String token = props.getToken() != null ? props.getToken() : "";
        return new UsernamePasswordCredentialsProvider(user, token);
    }
}
