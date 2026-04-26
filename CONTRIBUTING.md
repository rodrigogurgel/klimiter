# Contributing

## Getting started

1. Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) to set up your local environment.
2. Check open issues or discussions before starting significant work.
3. For larger changes, open an issue first to align on scope and approach.

## Commit conventions

All commits must follow the [Conventional Commits](https://www.conventionalcommits.org) specification. The changelog is generated automatically from commit messages using git-cliff, so correct types are required.

```
<type>(<scope>): <subject>
```

Allowed types and their changelog sections:

| Type | Section |
|---|---|
| `feat` | 🚀 Features |
| `fix` | 🐛 Bug Fixes |
| `refactor` | 🚜 Refactor |
| `docs` | 📚 Documentation |
| `perf` | ⚡ Performance |
| `style` | 🎨 Styling |
| `test` | 🧪 Testing |
| `chore` / `ci` | ⚙️ Miscellaneous Tasks |
| `revert` | ◀️ Revert |

Scopes follow module names: `core`, `redis`, `service`, `architecture`, `project`.

Breaking changes must use a `!` suffix or a `BREAKING CHANGE:` footer:

```
feat(core)!: change SPI interface for RateLimitOperation
```

`chore(release):`, `chore(deps*)`, `chore(pr)`, and `chore(pull)` commits are excluded from the changelog automatically.

## Branch model

- Work on a feature branch off `main`.
- Keep branches short-lived and focused on a single concern.
- Submit a pull request when the branch is ready for review.

## Pull request checklist

- [ ] Tests pass: `./gradlew test -x detekt`
- [ ] Architecture tests pass: `./gradlew :klimiter-architecture-tests:test`
- [ ] No detekt violations: `./gradlew detekt`
- [ ] Commit messages follow Conventional Commits
- [ ] Public API changes are reflected in the relevant `docs/` file
- [ ] If a new SPI interface is added, it belongs under `api/spi` and is `internal`-free

## Code style

- Kotlin 2.x, JVM 21 toolchain.
- 4-space indentation, LF line endings, 120-char line length for `.kt`/`.kts` — enforced by `.editorconfig`.
- Static analysis runs via Detekt with ktlint rules (`config/detekt/detekt.yml`). Fix violations before submitting; skip with `-x detekt` only during active development.
- `internal` visibility is the default for non-API types. Do not promote a type to `public` without placing it under `io.klimiter.*.api`.
- All rate-limit call paths must be `suspend`; no blocking I/O.

## Module boundaries

The dependency direction is strict and verified by architecture tests:

```
klimiter-service ──▶ klimiter-redis ──▶ klimiter-core
       │                                    ▲
       └────────────────────────────────────┘
```

- `klimiter-core` must not depend on Redis, Spring, or gRPC.
- `klimiter-redis` must not depend on `klimiter-service`.
- Service domain and application layers must not import transport or backend adapters directly.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full dependency rules.

## Changelog

The `CHANGELOG.md` is generated automatically; do not edit it by hand. See [docs/RELEASE.md](docs/RELEASE.md) for how the release process works.
