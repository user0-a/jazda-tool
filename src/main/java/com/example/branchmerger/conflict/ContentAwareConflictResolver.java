package com.example.branchmerger.conflict;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeChunk;
import org.eclipse.jgit.merge.MergeChunk.ConflictState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves merge conflicts by looking inside each conflicting region.
 *
 * For every conflicted file it reads the three index stages (base / ours /
 * theirs), re-runs JGit's three-way {@link MergeAlgorithm} to get structured
 * conflict chunks, and asks the configured {@link ContentConflictRule}s to
 * resolve each conflicting region based on its content. If every hunk in every
 * file is resolved, the resolved files are written and staged and the merge can
 * be committed. If any hunk cannot be resolved, it gives up so the service
 * aborts the merge.
 */
@Component
@Primary
public class ContentAwareConflictResolver implements ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(ContentAwareConflictResolver.class);

    private final List<ContentConflictRule> rules;
    private final MergeAlgorithm mergeAlgorithm = new MergeAlgorithm();

    public ContentAwareConflictResolver(List<ContentConflictRule> rules) {
        this.rules = rules;
    }

    @Override
    public boolean resolve(Git git, org.eclipse.jgit.api.MergeResult mergeResult) throws Exception {
        Repository repo = git.getRepository();
        DirCache dirCache = repo.readDirCache();
        Set<String> paths = conflictedPaths(dirCache);

        for (String path : paths) {
            if (!resolveFile(git, repo, dirCache, path)) {
                log.info("Could not fully resolve conflicts in {}; giving up.", path);
                return false;
            }
            // The index changed after staging; reload for the next file.
            dirCache = repo.readDirCache();
        }
        return true;
    }

    private boolean resolveFile(Git git, Repository repo, DirCache dirCache, String path) throws Exception {
        byte[] base = stageContent(repo, dirCache, path, DirCacheEntry.STAGE_1);
        byte[] ours = stageContent(repo, dirCache, path, DirCacheEntry.STAGE_2);
        byte[] theirs = stageContent(repo, dirCache, path, DirCacheEntry.STAGE_3);

        // Delete/modify conflicts (a side is missing) aren't content-mergeable here.
        if (ours == null || theirs == null) {
            return false;
        }
        if (base == null) {
            base = new byte[0]; // add/add conflict: no common ancestor
        }
        if (RawText.isBinary(ours) || RawText.isBinary(theirs)) {
            return false;
        }

        RawText baseRt = new RawText(base);
        RawText oursRt = new RawText(ours);
        RawText theirsRt = new RawText(theirs);
        RawText[] seqs = { baseRt, oursRt, theirsRt }; // sequence indices: 0=base, 1=ours, 2=theirs

        org.eclipse.jgit.merge.MergeResult<RawText> mr =
                mergeAlgorithm.merge(RawTextComparator.DEFAULT, baseRt, oursRt, theirsRt);

        String baseFileText = new String(base, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();

        String pendingOursText = null;
        List<String> pendingOursLines = null;

        for (MergeChunk chunk : mr) {
            RawText seq = seqs[chunk.getSequenceIndex()];
            String text = seq.getString(chunk.getBegin(), chunk.getEnd(), false);
            ConflictState state = chunk.getConflictState();

            if (state == ConflictState.NO_CONFLICT) {
                out.append(text);
            } else if (state == ConflictState.FIRST_CONFLICTING_RANGE) {
                // "ours" side of a conflict region
                pendingOursText = text;
                pendingOursLines = linesOf(seq, chunk.getBegin(), chunk.getEnd());
            } else { // NEXT_CONFLICTING_RANGE -> "theirs" side; now we have both
                List<String> theirsLines = linesOf(seq, chunk.getBegin(), chunk.getEnd());
                ConflictHunk hunk = new ConflictHunk(
                        path, pendingOursText, text, pendingOursLines, theirsLines, baseFileText);

                Optional<String> resolved = applyRules(hunk);
                if (resolved.isEmpty()) {
                    return false; // no rule handled this hunk
                }
                out.append(resolved.get());
                pendingOursText = null;
                pendingOursLines = null;
            }
        }

        // Write resolved content and stage it (clears the conflict / index stages).
        Files.write(repo.getWorkTree().toPath().resolve(path),
                out.toString().getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        log.info("Resolved conflicts in {}", path);
        return true;
    }

    private Optional<String> applyRules(ConflictHunk hunk) {
        for (ContentConflictRule rule : rules) {
            if (!rule.appliesTo(hunk.path())) {
                continue;
            }
            Optional<String> result = rule.resolve(hunk);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }

    private Set<String> conflictedPaths(DirCache dirCache) {
        Set<String> paths = new LinkedHashSet<>();
        for (int i = 0; i < dirCache.getEntryCount(); i++) {
            DirCacheEntry e = dirCache.getEntry(i);
            if (e.getStage() != DirCacheEntry.STAGE_0) {
                paths.add(e.getPathString());
            }
        }
        return paths;
    }

    private byte[] stageContent(Repository repo, DirCache dirCache, String path, int stage) throws IOException {
        for (int i = 0; i < dirCache.getEntryCount(); i++) {
            DirCacheEntry e = dirCache.getEntry(i);
            if (e.getPathString().equals(path) && e.getStage() == stage) {
                return repo.open(e.getObjectId()).getBytes();
            }
        }
        return null;
    }

    private List<String> linesOf(RawText rt, int begin, int end) {
        List<String> lines = new ArrayList<>(end - begin);
        for (int i = begin; i < end; i++) {
            lines.add(rt.getString(i)); // line content without terminator
        }
        return lines;
    }
}
