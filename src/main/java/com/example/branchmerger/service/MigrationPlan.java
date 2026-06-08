package com.example.branchmerger.service;

/**
 * The deterministic plan for normalizing the migration folders after a merge,
 * computed from the feature / main / merge-base trees before merging.
 */
public class MigrationPlan {

    private final boolean hasMigration;
    private final String oldMigrationPath;       // feature's migration at its original path
    private final boolean oldMigrationPathIsMain; // original path is also one of main's migrations
    private final String newMigrationPath;       // where feature's migration should live (maxMain+1)
    private final byte[] migrationContent;

    private final boolean hasRollback;
    private final String newRollbackPath;        // the single rollback file to keep, renumbered
    private final byte[] rollbackContent;

    private final int newNumber;

    private MigrationPlan(boolean hasMigration, String oldMigrationPath, boolean oldMigrationPathIsMain,
                          String newMigrationPath, byte[] migrationContent,
                          boolean hasRollback, String newRollbackPath, byte[] rollbackContent,
                          int newNumber) {
        this.hasMigration = hasMigration;
        this.oldMigrationPath = oldMigrationPath;
        this.oldMigrationPathIsMain = oldMigrationPathIsMain;
        this.newMigrationPath = newMigrationPath;
        this.migrationContent = migrationContent;
        this.hasRollback = hasRollback;
        this.newRollbackPath = newRollbackPath;
        this.rollbackContent = rollbackContent;
        this.newNumber = newNumber;
    }

    public static MigrationPlan nothingToDo() {
        return new MigrationPlan(false, null, false, null, null, false, null, null, -1);
    }

    public static MigrationPlan of(String oldMigrationPath, boolean oldMigrationPathIsMain,
                                   String newMigrationPath, byte[] migrationContent,
                                   boolean hasRollback, String newRollbackPath, byte[] rollbackContent,
                                   int newNumber) {
        return new MigrationPlan(true, oldMigrationPath, oldMigrationPathIsMain, newMigrationPath,
                migrationContent, hasRollback, newRollbackPath, rollbackContent, newNumber);
    }

    public boolean hasMigration() {
        return hasMigration;
    }

    public String oldMigrationPath() {
        return oldMigrationPath;
    }

    public boolean oldMigrationPathIsMain() {
        return oldMigrationPathIsMain;
    }

    public String newMigrationPath() {
        return newMigrationPath;
    }

    public byte[] migrationContent() {
        return migrationContent;
    }

    public boolean hasRollback() {
        return hasRollback;
    }

    public String newRollbackPath() {
        return newRollbackPath;
    }

    public byte[] rollbackContent() {
        return rollbackContent;
    }

    public int newNumber() {
        return newNumber;
    }
}
