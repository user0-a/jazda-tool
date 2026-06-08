package com.example.branchmerger.conflict;

import java.util.List;

/**
 * One conflicting region within a file, with the content from both sides.
 *
 * <ul>
 *   <li>{@code ours}   = the target branch's version of this region</li>
 *   <li>{@code theirs} = main's version of this region</li>
 * </ul>
 *
 * Both are provided as the raw text block (with original line terminators) and
 * as a list of individual lines (without terminators) for convenience. The full
 * base (common-ancestor) file text is available for context.
 */
public class ConflictHunk {

    private final String path;
    private final String oursText;
    private final String theirsText;
    private final List<String> oursLines;
    private final List<String> theirsLines;
    private final String baseFileText;

    public ConflictHunk(String path,
                        String oursText,
                        String theirsText,
                        List<String> oursLines,
                        List<String> theirsLines,
                        String baseFileText) {
        this.path = path;
        this.oursText = oursText;
        this.theirsText = theirsText;
        this.oursLines = oursLines;
        this.theirsLines = theirsLines;
        this.baseFileText = baseFileText;
    }

    public String path() {
        return path;
    }

    public String oursText() {
        return oursText;
    }

    public String theirsText() {
        return theirsText;
    }

    public List<String> oursLines() {
        return oursLines;
    }

    public List<String> theirsLines() {
        return theirsLines;
    }

    public String baseFileText() {
        return baseFileText;
    }
}
