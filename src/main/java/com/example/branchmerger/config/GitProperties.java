package com.example.branchmerger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration bound from the "git" prefix in application.yml
 * (or overridden via env vars, e.g. GIT_TOKEN).
 */
@ConfigurationProperties(prefix = "git")
public class GitProperties {

    /** Absolute path to a local clone of the repository on the machine running this app. */
    private String repoPath;

    /** Name of the remote to fetch from / push to. */
    private String remote = "origin";

    /** Name of the branch that gets merged into the target branch. */
    private String mainBranch = "main";

    /** GitHub username (for a PAT this can be the username or "x-access-token"). */
    private String username;

    /** GitHub Personal Access Token (or password). Prefer setting via env var GIT_TOKEN. */
    private String token;

    /** Folder (relative to repo root) holding the append-only migration history. */
    private String migrationsDir = "migrations";

    /** Folder holding the single current rollback script. */
    private String rollbackDir = "migration-rollback";

    /** Filename prefix before the number, e.g. "V" for V006784_name.sql. */
    private String migrationPrefix = "V";

    /** Filename suffix, e.g. ".sql". */
    private String migrationSuffix = ".sql";

    /**
     * Repo-relative paths whose feature-branch version is always kept, no matter
     * what main did (conflict or not). Defaults to the "pvt" file at the repo root.
     */
    private List<String> keepOursPaths = List.of("pvt");

    /** File holding the version marker that the optional currentVersionUpgrade rewrites. */
    private String versionFile = "pvt";

    /** Marker that precedes the version number on its line. */
    private String versionMarkerPrefix = "// currentVersion:";

    public String getRepoPath() {
        return repoPath;
    }

    public void setRepoPath(String repoPath) {
        this.repoPath = repoPath;
    }

    public String getRemote() {
        return remote;
    }

    public void setRemote(String remote) {
        this.remote = remote;
    }

    public String getMainBranch() {
        return mainBranch;
    }

    public void setMainBranch(String mainBranch) {
        this.mainBranch = mainBranch;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getMigrationsDir() {
        return migrationsDir;
    }

    public void setMigrationsDir(String migrationsDir) {
        this.migrationsDir = migrationsDir;
    }

    public String getRollbackDir() {
        return rollbackDir;
    }

    public void setRollbackDir(String rollbackDir) {
        this.rollbackDir = rollbackDir;
    }

    public String getMigrationPrefix() {
        return migrationPrefix;
    }

    public void setMigrationPrefix(String migrationPrefix) {
        this.migrationPrefix = migrationPrefix;
    }

    public String getMigrationSuffix() {
        return migrationSuffix;
    }

    public void setMigrationSuffix(String migrationSuffix) {
        this.migrationSuffix = migrationSuffix;
    }

    public List<String> getKeepOursPaths() {
        return keepOursPaths;
    }

    public void setKeepOursPaths(List<String> keepOursPaths) {
        this.keepOursPaths = keepOursPaths;
    }

    public String getVersionFile() {
        return versionFile;
    }

    public void setVersionFile(String versionFile) {
        this.versionFile = versionFile;
    }

    public String getVersionMarkerPrefix() {
        return versionMarkerPrefix;
    }

    public void setVersionMarkerPrefix(String versionMarkerPrefix) {
        this.versionMarkerPrefix = versionMarkerPrefix;
    }
}
