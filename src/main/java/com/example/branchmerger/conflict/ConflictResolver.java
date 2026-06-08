package com.example.branchmerger.conflict;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;

/**
 * Strategy for handling a conflicting merge.
 *
 * This is the hook for the conflict-resolution rules you mentioned. When the
 * merge of main into the target branch produces conflicts, the service hands
 * control here. Implement your rules (e.g. "always take theirs for *.lock",
 * "prefer ours for src/config/**", auto-resolve specific files, etc.) in a
 * custom implementation and register it as a Spring bean.
 */
public interface ConflictResolver {

    /**
     * Attempt to resolve a conflicting merge.
     *
     * @param git       the open repository, currently in a conflicted MERGING state
     * @param mergeResult the JGit result describing the conflicts
     * @return true if the conflict was fully resolved and the merge can be committed,
     *         false if it should be aborted
     * @throws Exception on any git error
     */
    boolean resolve(Git git, MergeResult mergeResult) throws Exception;
}
