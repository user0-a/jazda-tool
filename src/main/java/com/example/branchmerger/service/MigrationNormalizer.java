package com.example.branchmerger.service;

import com.example.branchmerger.config.GitProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the migration-folder rules after a merge:
 *
 * <ul>
 *   <li><b>migrations/</b>: the feature branch's single new migration is renumbered to
 *       (highest number on main) + 1, keeping its descriptive name. Main's history is
 *       left untouched.</li>
 *   <li><b>migration-rollback/</b>: every file is removed and replaced by the feature
 *       branch's rollback, renumbered to the same new number.</li>
 * </ul>
 *
 * The plan is computed from the feature, main and merge-base trees BEFORE the merge,
 * then applied AFTER, so it works whether or not git reported a conflict.
 */
@Service
public class MigrationNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MigrationNormalizer.class);

    private final GitProperties props;
    private final Pattern filename;

    public MigrationNormalizer(GitProperties props) {
        this.props = props;
        this.filename = Pattern.compile("^"
                + Pattern.quote(props.getMigrationPrefix())
                + "(\\d+)_(.*)"
                + Pattern.quote(props.getMigrationSuffix())
                + "$");
    }

    /** Computes the plan from the current branch vs the remote main. Call before merging. */
    public MigrationPlan computePlan(Git git, String branch) throws IOException {
        Repository repo = git.getRepository();
        ObjectId featureId = repo.resolve(branch);
        ObjectId mainId = repo.resolve(props.getRemote() + "/" + props.getMainBranch());
        if (featureId == null || mainId == null) {
            return MigrationPlan.nothingToDo();
        }

        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit feature = walk.parseCommit(featureId);
            RevCommit main = walk.parseCommit(mainId);
            RevCommit base = mergeBase(repo, feature, main);

            Map<String, ObjectId> baseMig = listDir(repo, base, props.getMigrationsDir());
            Map<String, ObjectId> featMig = listDir(repo, feature, props.getMigrationsDir());
            Map<String, ObjectId> mainMig = listDir(repo, main, props.getMigrationsDir());

            // Feature's new migration = present on feature, absent at the merge base.
            List<String> added = new ArrayList<>(featMig.keySet());
            added.removeAll(baseMig.keySet());
            if (added.isEmpty()) {
                log.info("No new migration on branch {} relative to merge base; nothing to renumber.", branch);
                return MigrationPlan.nothingToDo();
            }
            if (added.size() > 1) {
                log.warn("Branch {} added {} migrations; only the lowest-numbered will be renumbered.",
                        branch, added.size());
            }
            added.sort(this::compareByNumber);
            String featureMigName = added.get(0);

            Parsed parsed = parse(featureMigName);
            if (parsed == null) {
                log.warn("Migration filename '{}' does not match the expected pattern; skipping.", featureMigName);
                return MigrationPlan.nothingToDo();
            }

            int maxMain = mainMig.keySet().stream()
                    .map(this::parse)
                    .filter(p -> p != null)
                    .mapToInt(p -> p.number)
                    .max()
                    .orElse(0);
            int newNumber = maxMain + 1;

            String newMigName = format(newNumber, parsed.width, parsed.name);
            String oldMigrationPath = path(props.getMigrationsDir(), featureMigName);
            String newMigrationPath = path(props.getMigrationsDir(), newMigName);
            byte[] migrationContent = content(repo, featMig.get(featureMigName));
            boolean oldIsMain = mainMig.containsKey(featureMigName);

            // Rollback: feature's new rollback file (present on feature, absent at base).
            Map<String, ObjectId> baseRb = listDir(repo, base, props.getRollbackDir());
            Map<String, ObjectId> featRb = listDir(repo, feature, props.getRollbackDir());
            List<String> rbAdded = new ArrayList<>(featRb.keySet());
            rbAdded.removeAll(baseRb.keySet());

            boolean hasRollback = false;
            String newRollbackPath = null;
            byte[] rollbackContent = null;
            if (!rbAdded.isEmpty()) {
                rbAdded.sort(this::compareByNumber);
                String featureRbName = rbAdded.get(0);
                Parsed rbParsed = parse(featureRbName);
                if (rbParsed != null) {
                    String newRbName = format(newNumber, rbParsed.width, rbParsed.name);
                    newRollbackPath = path(props.getRollbackDir(), newRbName);
                    rollbackContent = content(repo, featRb.get(featureRbName));
                    hasRollback = true;
                }
            } else {
                log.warn("No new rollback file found on branch {}; rollback folder will only be cleaned.", branch);
            }

            return MigrationPlan.of(oldMigrationPath, oldIsMain, newMigrationPath, migrationContent,
                    hasRollback, newRollbackPath, rollbackContent, newNumber);
        }
    }

    /**
     * Applies the plan to the working tree and index of an already-merged branch.
     *
     * @return true if any change was staged (and therefore a commit is needed)
     */
    public boolean apply(Git git, MigrationPlan plan) throws Exception {
        if (!plan.hasMigration()) {
            return false;
        }
        Repository repo = git.getRepository();
        boolean changed = false;

        // --- migrations folder ---
        if (!plan.oldMigrationPath().equals(plan.newMigrationPath())) {
            // Remove feature's old-numbered file, unless that exact name is one of main's migrations.
            if (!plan.oldMigrationPathIsMain() && tracked(repo, plan.oldMigrationPath())) {
                git.rm().addFilepattern(plan.oldMigrationPath()).call();
            }
        }
        writeAndStage(git, repo, plan.newMigrationPath(), plan.migrationContent());
        changed = true;

        // --- migration-rollback folder: keep only the feature's rollback, renumbered ---
        for (String existing : trackedUnder(repo, props.getRollbackDir())) {
            git.rm().addFilepattern(existing).call();
        }
        if (plan.hasRollback()) {
            writeAndStage(git, repo, plan.newRollbackPath(), plan.rollbackContent());
        }

        log.info("Migration normalized to number {} (migration -> {}, rollback -> {})",
                plan.newNumber(), plan.newMigrationPath(), plan.newRollbackPath());
        return changed;
    }

    // ---------- helpers ----------

    private void writeAndStage(Git git, Repository repo, String relPath, byte[] content) throws Exception {
        java.nio.file.Path target = repo.getWorkTree().toPath().resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        git.add().addFilepattern(relPath).call();
    }

    private boolean tracked(Repository repo, String path) throws IOException {
        DirCache dc = repo.readDirCache();
        return dc.findEntry(path) >= 0;
    }

    private List<String> trackedUnder(Repository repo, String dir) throws IOException {
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        List<String> result = new ArrayList<>();
        DirCache dc = repo.readDirCache();
        for (int i = 0; i < dc.getEntryCount(); i++) {
            DirCacheEntry e = dc.getEntry(i);
            if (e.getPathString().startsWith(prefix)) {
                result.add(e.getPathString());
            }
        }
        return result;
    }

    private RevCommit mergeBase(Repository repo, RevCommit a, RevCommit b) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            return walk.next();
        }
    }

    /** Lists files directly under {@code dir} in a commit's tree: filename -> blob id. */
    private Map<String, ObjectId> listDir(Repository repo, RevCommit commit, String dir) throws IOException {
        Map<String, ObjectId> out = new LinkedHashMap<>();
        if (commit == null) {
            return out;
        }
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while (tw.next()) {
                String p = tw.getPathString();
                if (p.startsWith(prefix)) {
                    String name = p.substring(prefix.length());
                    if (!name.contains("/")) {
                        out.put(name, tw.getObjectId(0));
                    }
                }
            }
        }
        return out;
    }

    private byte[] content(Repository repo, ObjectId blob) throws IOException {
        return repo.open(blob).getBytes();
    }

    private Parsed parse(String name) {
        Matcher m = filename.matcher(name);
        if (!m.matches()) {
            return null;
        }
        String digits = m.group(1);
        return new Parsed(Integer.parseInt(digits), digits.length(), m.group(2));
    }

    private int compareByNumber(String a, String b) {
        Parsed pa = parse(a);
        Parsed pb = parse(b);
        int na = pa != null ? pa.number : Integer.MAX_VALUE;
        int nb = pb != null ? pb.number : Integer.MAX_VALUE;
        return Integer.compare(na, nb);
    }

    private String format(int number, int width, String name) {
        return props.getMigrationPrefix()
                + String.format("%0" + width + "d", number)
                + "_" + name + props.getMigrationSuffix();
    }

    private String path(String dir, String name) {
        return (dir.endsWith("/") ? dir : dir + "/") + name;
    }

    private static final class Parsed {
        final int number;
        final int width;
        final String name;

        Parsed(int number, int width, String name) {
            this.number = number;
            this.width = width;
            this.name = name;
        }
    }
}
