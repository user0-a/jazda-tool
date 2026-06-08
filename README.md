# branch-merger

A Spring Boot service that takes a branch name, merges the latest `main` into
that branch, and pushes the result to GitHub. Built on
[JGit](https://www.eclipse.org/jgit/) so no `git` binary is required.

## What it does

1. `fetch` from the remote
2. checks out the target branch (creating a local tracking branch if needed) and
   hard-resets it to the remote so the starting state is clean
3. merges `origin/main` into the target branch
4. on success, pushes the branch back to the remote
5. on conflict, hands off to a `ConflictResolver` (see below). Until rules exist,
   it aborts the merge and reports the conflicting files.

## Run it

You need a **local clone** of the repo on the machine running this app, plus a
GitHub Personal Access Token with `repo` scope.

```bash
export GIT_REPO_PATH=/absolute/path/to/your/clone
export GIT_USERNAME=x-access-token        # or your GitHub username
export GIT_TOKEN=ghp_your_pat_here

mvn spring-boot:run
```

## Call it

```bash
curl -X POST http://localhost:8080/api/merge \
  -H "Content-Type: application/json" \
  -d '{"branch": "feature/login"}'
```

Example responses:

```json
{ "status": "MERGED_AND_PUSHED", "branch": "feature/login",
  "message": "Merged main into feature/login and pushed.",
  "mergeCommitId": "a1b2c3..." }
```

```json
{ "status": "CONFLICTS", "branch": "feature/login",
  "message": "Merge produced conflicts; no resolution rules applied. Merge aborted.",
  "conflictingFiles": ["src/App.java", "pom.xml"] }
```

HTTP status codes: 200 (merged / up-to-date), 409 (conflicts), 422 (failed).

## Content-aware conflict resolution

When the merge conflicts, `ContentAwareConflictResolver` (the active `@Primary`
`ConflictResolver`) handles it by looking *inside* each conflicting region:

1. For each conflicted file it reads the three index stages — base (common
   ancestor), ours (target branch), theirs (main).
2. It re-runs JGit's three-way `MergeAlgorithm` on those blobs to get
   **structured conflict chunks** (no parsing of `<<<<<<<` text markers).
3. Each conflict hunk is passed through an ordered chain of
   `ContentConflictRule`s. The first rule that returns resolved text wins.
4. Non-conflicting regions are copied through unchanged; resolved regions are
   substituted; the rebuilt file is written and `git add`-ed.
5. If **every** hunk in **every** file is resolved, the service commits and
   pushes. If any hunk has no matching rule, the whole merge is aborted and the
   conflicting files are reported (nothing is pushed).

### Rules shipped

Run in `@Order`, lowest first:

| Order | Rule | What it does |
|------:|------|--------------|
| 0   | `UnionImportsRule`          | Both sides only added Java `import`s → keep the sorted, de-duplicated union of both. |
| 10  | `HigherSemverRule`          | Both sides are a single version line → keep the higher version, preserving its formatting. |
| 100 | `PreferMainForLockfilesRule`| For `*.lock` / `package-lock.json` etc., take main's version (last-resort, path-scoped). |

### Adding your own rule

Implement `ContentConflictRule`, annotate with `@Component` and an `@Order`, and
return the resolved text block (or `Optional.empty()` to pass to the next rule):

```java
@Component
@Order(20)
public class MyRule implements ContentConflictRule {

    @Override
    public boolean appliesTo(String path) {
        return path.endsWith(".properties");   // optional path filter
    }

    @Override
    public Optional<String> resolve(ConflictHunk hunk) {
        // hunk.oursLines() / hunk.theirsLines()  -> lines from each side
        // hunk.oursText()  / hunk.theirsText()   -> raw blocks (with newlines)
        // hunk.baseFileText()                     -> common-ancestor file, for context
        // return Optional.of(resolvedBlockWithTrailingNewlines) when you can decide
        return Optional.empty();
    }
}
```

`ConflictHunk.theirs*` is always main's side; `ours*` is the target branch's
side. Returned text is concatenated directly into the rebuilt file, so include
trailing newlines.

> Note: content merging operates on text (UTF-8). Binary files and
> delete/modify conflicts are treated as unresolved and abort the merge.

## Migration folders (`migrations/` + `migration-rollback/`)

These two folders follow fixed rules instead of line merging, enforced by
`MigrationNormalizer` as a **post-merge normalization** (not conflict handling —
most of the time git reports no conflict because the filenames differ):

- **`migrations/`** — append-only Flyway-style history (`V<number>_<name>.sql`).
  The feature branch's single new migration is renumbered to *(highest number on
  main) + 1*, keeping its descriptive name and zero-pad width. Main's migrations
  are left untouched. Example: branch adds `V006784_add_orders.sql`, main's
  latest is `V006790_*` → the branch's file becomes `V006791_add_orders.sql`.
- **`migration-rollback/`** — every file is deleted and replaced by the feature
  branch's rollback file, renumbered to the same new number. After the merge the
  folder contains exactly one file. (This is destructive by design — main's
  rollback files are removed.)

How it works: before merging, the service compares the feature branch, main, and
their merge-base to find the feature's new migration/rollback and main's highest
number, producing a `MigrationPlan`. After the merge it applies the renames and
deletions and commits them as a separate "Renumber migration …" commit.

The rare case where the branch and main add files with the *same* number **and**
name (a real add/add conflict) is handled by `PreferMainForMigrationsRule`, which
keeps main's file; normalization then places the branch's content at the new
number.

Config (in `application.yml`, all overridable):

```yaml
git:
  migrations-dir: migrations
  rollback-dir: migration-rollback
  migration-prefix: V        # V006784_name.sql
  migration-suffix: .sql
```

Assumptions: exactly one new migration per feature branch; numbers are sequential
integers (not timestamps). Both repos are Maven Spring Boot projects, so the
`pom.xml` `<version>` conflict is covered by `HigherSemverRule` and the
JS-oriented `PreferMainForLockfilesRule` is inert (safe to delete).

## Feature-owned files (`pvt`)

Files listed under `git.keep-ours-paths` (default: `pvt`) always keep the feature
branch's version, no matter what main did:

- On conflict (both branches changed it), `KeepFeatureVersionRule` takes the
  feature side.
- With no conflict (only main changed it, so git would silently take main's
  version), a post-merge step restores the file from `origin/<branch>` — the
  feature tip — so the feature version wins unconditionally.

Add more such files in `application.yml`:

```yaml
git:
  keep-ours-paths:
    - pvt
    - config/local.env
```

### Optional version bump (`currentVersionUpgrade`)

Send `currentVersionUpgrade=true` in the request to rewrite a version marker line
in the version file (default `pvt`) to **main's** version with its last segment
incremented by one:

```json
{ "branch": "my-feature", "currentVersionUpgrade": true }
```

If main's `pvt` has `// currentVersion: 3.4.2313`, the feature branch's `pvt`
marker line becomes `// currentVersion: 3.4.2314`. The rest of `pvt` stays the
feature's version. Off by default. Configure the file and marker:

```yaml
git:
  version-file: pvt
  version-marker-prefix: "// currentVersion:"
```

## Assumptions I made

- **REST endpoint** as the input mechanism (vs. a CLI). Easy to swap.
- **Local clone already exists** at `GIT_REPO_PATH`. I can add an auto-clone step
  if you'd rather point it at a remote URL instead.
- **PAT over HTTPS** for auth. SSH key auth is also doable.
- The target branch is **hard-reset to its remote** before merging, so any local
  uncommitted state in that clone is discarded. Tell me if you need that gentler.
