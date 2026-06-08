package com.example.branchmerger.conflict.rules;

import com.example.branchmerger.conflict.ConflictHunk;
import com.example.branchmerger.conflict.ContentConflictRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * For machine-generated / lock files, hand-merging is pointless, so take main's
 * version of any conflicting region. This is path-scoped (only the listed file
 * kinds) and content-blind by design — it's the safe choice precisely for files
 * that are regenerated rather than edited by hand.
 *
 * Runs late (high @Order) so the content-aware rules get first crack.
 */
@Component
@Order(100)
public class PreferMainForLockfilesRule implements ContentConflictRule {

    private static final List<String> LOCKFILE_SUFFIXES = List.of(
            ".lock",
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "poetry.lock",
            "Gemfile.lock"
    );

    @Override
    public boolean appliesTo(String path) {
        return LOCKFILE_SUFFIXES.stream().anyMatch(path::endsWith);
    }

    @Override
    public Optional<String> resolve(ConflictHunk hunk) {
        // "theirs" is main's side of the conflict.
        return Optional.of(hunk.theirsText());
    }
}
