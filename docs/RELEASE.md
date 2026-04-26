# Release Process

Releases are version-tagged commits on `main`. The changelog is generated automatically from commit history by [git-cliff](https://git-cliff.org) using the configuration in `cliff.toml`.

## Prerequisites

- `git-cliff` 2.x installed locally (`brew install git-cliff` / `cargo install git-cliff`).
- Write access to the repository.
- Clean working tree on an up-to-date `main` branch.

## Versioning

KLimiter follows [Semantic Versioning](https://semver.org):

- **PATCH** (`x.y.Z`) — backwards-compatible bug fixes.
- **MINOR** (`x.Y.0`) — new backwards-compatible features.
- **MAJOR** (`X.0.0`) — breaking changes (commits with `!` suffix or `BREAKING CHANGE:` footer).

## Changelog automation

`CHANGELOG.md` is generated from Conventional Commits grouped by type. The git-cliff configuration (`cliff.toml`) defines:

- Which commit types are included and how they map to sections.
- The Tera template used to render each release block.
- The sort order (oldest-first within each group).

`chore(release):`, `chore(deps*)`, `chore(pr)`, and `chore(pull)` commits are automatically excluded from the changelog.

### Changelog commands

```bash
# Generate / update CHANGELOG.md from all commits
task changelog
# or
make changelog
# or directly
git-cliff -c cliff.toml -o CHANGELOG.md

# Preview the changelog without writing the file
task changelog:dry-run
# or
git-cliff -c cliff.toml

# Verify the on-disk changelog matches what git-cliff would produce
task changelog:check
# or
make changelog-check
```

## Step-by-step release

### 1. Confirm the branch is ready

```bash
git checkout main
git pull
./gradlew test -x detekt
./gradlew :klimiter-architecture-tests:test
```

### 2. Preview the changelog

```bash
git-cliff -c cliff.toml --tag vX.Y.Z
```

Review the output and make sure all significant commits are present and correctly categorised.

### 3. Generate the updated changelog

```bash
git-cliff -c cliff.toml --tag vX.Y.Z -o CHANGELOG.md
```

### 4. Commit the changelog

```bash
git add CHANGELOG.md
git commit -m "chore(release): prepare for vX.Y.Z"
```

`chore(release):` commits are filtered out of the changelog automatically.

### 5. Tag the release

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
```

git-cliff uses annotated tags by default.

### 6. Push

```bash
git push origin main --follow-tags
```

## Post-release

After tagging:

- Verify that `CHANGELOG.md` at the tag reflects the new version block.
- If the project is published to a package registry, trigger the publish workflow.

## Hotfix releases

A hotfix follows the same process on a dedicated `hotfix/vX.Y.Z` branch:

1. Branch from the target release tag: `git checkout -b hotfix/vX.Y.Z vX.Y.{Z-1}`.
2. Apply the fix with a `fix(scope): ...` commit.
3. Run tests.
4. Follow steps 2–6 above, then merge the hotfix branch back into `main`.

## Unreleased changes

Between releases, `CHANGELOG.md` carries an `## [unreleased]` section at the top. This section is rebuilt on every `task changelog` run and reflects all commits since the last tag.
