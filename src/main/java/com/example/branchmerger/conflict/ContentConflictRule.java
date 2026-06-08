package com.example.branchmerger.conflict;

import java.util.Optional;

/**
 * A single content-aware rule. Rules are tried in order (lowest Spring @Order
 * first) for each conflict hunk; the first one that returns a value wins.
 *
 * Implement {@link #resolve} to inspect the actual content of both sides and,
 * if your rule knows what to do, return the resolved text block that should
 * replace the conflict region. Return {@link Optional#empty()} to pass the hunk
 * to the next rule.
 *
 * Returned text should include trailing newlines as appropriate, since it is
 * concatenated directly into the rebuilt file.
 */
public interface ContentConflictRule {

    /** Optional cheap path filter. Default: applies to every file. */
    default boolean appliesTo(String path) {
        return true;
    }

    Optional<String> resolve(ConflictHunk hunk);
}
