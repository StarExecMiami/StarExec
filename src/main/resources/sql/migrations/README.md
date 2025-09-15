# Flyway filesystem migrations (optional)

Use this folder to stage legacy .sql while you convert them into versioned migrations under `src/main/resources/db/migration`.

## Conventions

- Versioned filenames: `V{version}__{description}.sql` (e.g. `V0002__create_users.sql`)
- Repeatable scripts: `R__{description}.sql` (re-run when changed)
- One change per migration: schema DDL or data backfill; keep small and ordered.

## Migrate from the current Ant setup

1. Baseline the existing database to the correct version tag:
   
   mvn -DskipTests flyway:baseline

2. Start creating forward-only migrations in `src/main/resources/db/migration`, starting after the baseline version.
3. For a clean install (new DB), Flyway will run `V0001..Vnnnn` in order.

## Running

- Local dev: `mvn -DskipTests flyway:migrate`
- Undo last migration (Flyway Teams only). For OSS, create a new migration that reverts.

## Configuration

- pom.xml reads DB creds from env: `STAREXEC_DB_HOST`, `STAREXEC_DB_NAME`, `STAREXEC_DB_USER`, `STAREXEC_DB_PASSWORD`.
- docker-compose already provides these env vars to the app container; run Flyway from host with matching env.
