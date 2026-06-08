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

        // Accumulate each conflict region's sides. A region may have zero, one, or
        // several chunks per side, and the "ours" side can be empty (no FIRST chunk),
        // so we buffer until the region ends (next NO_CONFLICT chunk or end of file).
        boolean inConflict = false;
        StringBuilder oursText = new StringBuilder();
        StringBuilder theirsText = new StringBuilder();
        List<String> oursLines = new ArrayList<>();
        List<String> theirsLines = new ArrayList<>();

        for (MergeChunk chunk : mr) {
            RawText seq = seqs[chunk.getSequenceIndex()];
            String text = textOf(seq, chunk.getBegin(), chunk.getEnd());
            ConflictState state = chunk.getConflictState();

            if (state == ConflictState.NO_CONFLICT) {
                if (inConflict) {
                    if (!flushConflict(path, baseFileText, oursText, theirsText,
                            oursLines, theirsLines, out)) {
                        return false;
                    }
                    inConflict = false;
                }
                out.append(text);
            } else if (state == ConflictState.FIRST_CONFLICTING_RANGE) {
                inConflict = true;
                oursText.append(text);
                oursLines.addAll(linesOf(seq, chunk.getBegin(), chunk.getEnd()));
            } else { // NEXT_CONFLICTING_RANGE -> "theirs" side
                inConflict = true;
                theirsText.append(text);
                theirsLines.addAll(linesOf(seq, chunk.getBegin(), chunk.getEnd()));
            }
        }
        if (inConflict && !flushConflict(path, baseFileText, oursText, theirsText,
                oursLines, theirsLines, out)) {
            return false;
        }

        // Write resolved content and stage it (clears the conflict / index stages).
        Files.write(repo.getWorkTree().toPath().resolve(path),
                out.toString().getBytes(StandardCharsets.UTF_8));
        git.add().addFilepattern(path).call();
        log.info("Resolved conflicts in {}", path);
        return true;
    }

    /**
     * Applies the rules to one accumulated conflict region and appends the result.
     * Returns false if no rule resolved it. Clears the buffers for the next region.
     */
    private boolean flushConflict(String path, String baseFileText,
                                  StringBuilder oursText, StringBuilder theirsText,
                                  List<String> oursLines, List<String> theirsLines,
                                  StringBuilder out) {
        ConflictHunk hunk = new ConflictHunk(path, oursText.toString(), theirsText.toString(),
                new ArrayList<>(oursLines), new ArrayList<>(theirsLines), baseFileText);
        Optional<String> resolved = applyRules(hunk);
        // Reset buffers regardless, ready for the next region.
        oursText.setLength(0);
        theirsText.setLength(0);
        oursLines.clear();
        theirsLines.clear();
        if (resolved.isEmpty()) {
            return false;
        }
        out.append(resolved.get());
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
        List<String> lines = new ArrayList<>(Math.max(0, end - begin));
        for (int i = begin; i < end; i++) {
            lines.add(rt.getString(i)); // line content without terminator
        }
        return lines;
    }

    /**
     * Builds the text for a chunk line by line using the single-arg getString(i),
     * which is safe for empty sequences and edge ranges (the 3-arg getString throws
     * on an empty base, e.g. add/add conflicts). Output is LF-terminated.
     */
    private String textOf(RawText rt, int begin, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = begin; i < end; i++) {
            sb.append(rt.getString(i)).append('\n');
        }
        return sb.toString();
    }
}
