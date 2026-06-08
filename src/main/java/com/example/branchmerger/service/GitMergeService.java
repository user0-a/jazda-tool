package com.example.branchmerger.service;

import com.example.branchmerger.config.GitProperties;
import com.example.branchmerger.conflict.ConflictResolver;
import com.example.branchmerger.dto.MergeResult;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
                    normalizeMigrations(git, plan, targetBranch);
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
                    return handleConflicts(git, targetBranch, remote, cp, merge, plan);

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
                                        MigrationPlan plan) throws Exception {

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
            normalizeMigrations(git, plan, targetBranch);
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

    /** Applies the migration/rollback renumbering and commits it if anything changed. */
    private void normalizeMigrations(Git git, MigrationPlan plan, String targetBranch) throws Exception {
        if (migrationNormalizer.apply(git, plan)) {
            git.commit()
                    .setMessage("Renumber migration to " + props.getMigrationPrefix() + plan.newNumber()
                            + " and reset rollback after merging "
                            + props.getMainBranch() + " into " + targetBranch)
                    .call();
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
