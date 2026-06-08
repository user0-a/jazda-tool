package com.example.branchmerger.conflict.rules;

import com.example.branchmerger.conflict.ConflictHunk;
import com.example.branchmerger.conflict.ContentConflictRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Classic "both sides added imports" conflict. If every non-blank line on both
 * sides is a Java import statement, resolve to the de-duplicated, sorted union
 * of all imports from both sides.
 */
@Component
@Order(0)
public class UnionImportsRule implements ContentConflictRule {

    @Override
    public boolean appliesTo(String path) {
        return path.endsWith(".java");
    }

    @Override
    public Optional<String> resolve(ConflictHunk hunk) {
        if (!allImports(hunk.oursLines()) || !allImports(hunk.theirsLines())) {
            return Optional.empty();
        }

        TreeSet<String> merged = new TreeSet<>();
        addImports(merged, hunk.oursLines());
        addImports(merged, hunk.theirsLines());

        StringBuilder sb = new StringBuilder();
        for (String imp : merged) {
            sb.append(imp).append('\n');
        }
        return Optional.of(sb.toString());
    }

    private boolean allImports(List<String> lines) {
        boolean sawImport = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!t.startsWith("import ")) {
                return false;
            }
            sawImport = true;
        }
        return sawImport;
    }

    private void addImports(TreeSet<String> target, List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("import ")) {
                target.add(t);
            }
        }
    }
}
