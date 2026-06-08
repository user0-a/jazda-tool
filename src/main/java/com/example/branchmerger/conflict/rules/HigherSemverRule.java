package com.example.branchmerger.conflict.rules;

import com.example.branchmerger.conflict.ConflictHunk;
import com.example.branchmerger.conflict.ContentConflictRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When both sides of a conflict are a single line containing a version number
 * (e.g. {@code <version>1.4.2</version>} or {@code version = "2.0.1"}), keep the
 * line with the higher version. Useful when main and the branch both bumped the
 * same version field. The full line of the winning side is preserved so its
 * surrounding formatting is kept intact.
 */
@Component
@Order(10)
public class HigherSemverRule implements ContentConflictRule {

    private static final Pattern VERSION =
            Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[.\\-+][0-9A-Za-z.\\-]+)?");

    @Override
    public Optional<String> resolve(ConflictHunk hunk) {
        String ourLine = singleContentLine(hunk.oursLines());
        String theirLine = singleContentLine(hunk.theirsLines());
        if (ourLine == null || theirLine == null) {
            return Optional.empty();
        }

        long[] ourV = parse(ourLine);
        long[] theirV = parse(theirLine);
        if (ourV == null || theirV == null) {
            return Optional.empty();
        }

        // Keep the higher version, preserving that side's exact text block.
        String winnerBlock = compare(ourV, theirV) >= 0 ? hunk.oursText() : hunk.theirsText();
        return Optional.of(winnerBlock);
    }

    /** Returns the sole non-blank line, or null if there isn't exactly one. */
    private String singleContentLine(List<String> lines) {
        String found = null;
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (found != null) {
                return null; // more than one content line
            }
            found = line;
        }
        return found;
    }

    private long[] parse(String line) {
        Matcher m = VERSION.matcher(line);
        if (!m.find()) {
            return null;
        }
        long major = Long.parseLong(m.group(1));
        long minor = Long.parseLong(m.group(2));
        long patch = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
        return new long[] { major, minor, patch };
    }

    private int compare(long[] a, long[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
