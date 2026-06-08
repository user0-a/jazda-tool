package com.example.branchmerger.conflict.rules;

import com.example.branchmerger.config.GitProperties;
import com.example.branchmerger.conflict.ConflictHunk;
import com.example.branchmerger.conflict.ContentConflictRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handles the rare case where the feature branch and main added a migration (or
 * rollback) file with the SAME number AND name, producing a real add/add conflict
 * on that path. We keep main's content there; the {@code MigrationNormalizer} then
 * places the feature's migration at the new (maxMain+1) number and rebuilds the
 * rollback folder. Without this rule the line-level resolver would have no way to
 * resolve a .sql conflict and the merge would abort.
 *
 * Runs before the content rules (negative order) for files in the migration folders.
 */
@Component
@Order(-100)
public class PreferMainForMigrationsRule implements ContentConflictRule {

    private final String migrationsPrefix;
    private final String rollbackPrefix;

    public PreferMainForMigrationsRule(GitProperties props) {
        this.migrationsPrefix = withSlash(props.getMigrationsDir());
        this.rollbackPrefix = withSlash(props.getRollbackDir());
    }

    @Override
    public boolean appliesTo(String path) {
        return path.startsWith(migrationsPrefix) || path.startsWith(rollbackPrefix);
    }

    @Override
    public Optional<String> resolve(ConflictHunk hunk) {
        return Optional.of(hunk.theirsText()); // keep main's side
    }

    private static String withSlash(String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }
}
