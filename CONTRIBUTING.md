# Contributing to StarExec

Thanks for contributing! For full setup, build, and debugging instructions, see the [Developer Guide](docs/DEVELOPER.md). For larger architectural or schema changes, please open an issue first to discuss the approach.

## How to Contribute

1. Fork the repository and create a feature branch from `containerised` (`<type>/<short-slug>`).
2. Place application code and tests in `starexec-app/` (never add code to the empty root `src/` directory).
3. Keep changes focused and add unit/integration tests covering your change.
4. Run the verification checks listed below before opening a pull request against `containerised`.
5. Open a pull request describing the runtime behavior change, affected components, and reproducible verification steps.

## Standards and Conventions

- **Java tests:** Use **JUnit 5** (`org.junit.jupiter.api.*`) exclusively. The `jupiter-only` Maven Enforcer rule rejects JUnit 4 and TestNG.
- **Database migrations:** Add new versioned Flyway migrations in `starexec-app/src/main/resources/db/migration/`. Never edit already-applied versioned migrations, never use `CREATE INDEX CONCURRENTLY`, and follow [Database Standards](docs/DATABASE_STANDARDS.md).
- **Web security:** Preserve CSRF protection (`CsrfFilter`), authorization checks (`GeneralSecurity` / `*Security`), contextual output escaping, and parameterized SQL.
- **Git hygiene and language:** Use English Conventional Commits (`<type>(<scope>): <summary>`). Repository hooks (`.githooks/pre-commit`, `.githooks/commit-msg`) reject non-English commit messages, local filesystem paths, and secret files.

## Verification Before Opening a PR

```bash
# Java unit suite (main module + credential handler)
mvn -q test -f starexec-app/pom.xml
mvn -q test -f tomcat-credential-handler/pom.xml

# Frontend JS regression suite & SCSS build
node --test scripts/tests/*.test.cjs
npm run sass-build:war

# Python & shell scripts
python3 -m pytest -q scripts/
for s in scripts/*.sh; do bash -n "$s"; done
```

## Reporting Bugs and Feature Requests

Use [GitHub Issues](https://github.com/StarExecMiami/StarExec/issues) and include logs, reproduction steps, backend type (`local`, `podman`, or `kubernetes`), and environment details.

## Security Issues

If you discover a security vulnerability, **do not open a public issue**. Follow our private disclosure process in [SECURITY.md](SECURITY.md).
