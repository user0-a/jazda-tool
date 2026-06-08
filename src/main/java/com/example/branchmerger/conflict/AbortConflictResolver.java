package com.example.branchmerger.conflict;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.springframework.stereotype.Component;

/**
 * Default behaviour until the real rules exist: do not attempt to resolve.
 * The service is responsible for aborting the merge and reporting the conflicts.
 *
 * Replace / supplement this with your rule-based resolver later. If you mark a
 * different ConflictResolver bean as @Primary, this one steps aside.
 */
@Component
public class AbortConflictResolver implements ConflictResolver {

    @Override
    public boolean resolve(Git git, MergeResult mergeResult) {
        // No automatic resolution yet.
        return false;
    }
}
