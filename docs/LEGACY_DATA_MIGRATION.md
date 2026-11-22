# Migrating Data from the Legacy Deployment

The `containerised` line replaces the legacy StarExec deployment (branch `UMprod`, running on MariaDB/MySQL). This guide documents the tooling and required steps to migrate legacy data into a Flyway-managed PostgreSQL `containerised` deployment.

## Prerequisites

- **Target schema already migrated by Flyway:** Start StarExec (or run Flyway migrations) on the empty PostgreSQL database so all `starexec.*` tables up through the target version (`V0137` in `v2.6.0`) exist before loading data.
- **Read-only access to the legacy MariaDB/MySQL source:** `scripts/mysql2pg/migrate.py` only executes `SELECT` queries against the source database.
- **Python 3 dependencies:** `pyyaml`, a MySQL driver (`pymysql`), and a PostgreSQL driver (`psycopg` or `psycopg2`).
- **PostgreSQL `pgcrypto` extension** (for legacy SHA-512 password wrapping).

## Environment Variables

Both `migrate.py` and `validate.py` read credentials exclusively from environment variables:

| Role | Variables | Defaults |
|------|-----------|----------|
| **Source (MariaDB / MySQL)** | `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DB` | `127.0.0.1`, `3306`, *(required)*, *(required)*, `starexec` |
| **Target (PostgreSQL)** | `DATABASE_URL` or `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE` | `127.0.0.1`, `5432`, `starexec`, `""`, `starexec` |

## Streaming ETL Migration (`scripts/mysql2pg`)

`scripts/mysql2pg/migrate.py` streams rows in foreign-key dependency order defined in `scripts/mysql2pg/manifest.yaml`, loads only shared columns via PostgreSQL `COPY`, converts MySQL zero dates safely, advances `SERIAL`/`IDENTITY` sequences, and runs `post_load_sql` steps (including legacy password wrapping).

### Step 1: Dry Run and Plan Inspection

```bash
python3 scripts/mysql2pg/migrate.py --dry-run
```

### Step 2: Load Data into Target PostgreSQL

If Flyway `V0002__seed_minimal_data.sql` populated initial rows (`admin`, `public`, default spaces/settings) in a fresh target database, pass `--clear-seed` to clear planned target tables in reverse FK order before loading:

```bash
export MYSQL_HOST=legacy-db.example.org
export MYSQL_USER=readonly_user
export MYSQL_PASSWORD='...'
export DATABASE_URL='postgresql://starexec:...@localhost:5432/starexec'

python3 scripts/mysql2pg/migrate.py --clear-seed --chunk-size 5000
```

Useful flags:
- `--tier <N>` (repeatable): restrict loading to specific dependency tiers (`0, 1, 2, 3`).
- `--table <name>` (repeatable): load explicit table(s) only.
- `--include-optional`: include optional tables defined in `manifest.yaml`.
- `--skip-post-sql`: skip `post_load_sql` hooks (useful if wrapping passwords separately).

### Step 3: Post-Load Validation

```bash
# Full source-to-target row count parity, PostgreSQL sequence bounds, and password wrap check
python3 scripts/mysql2pg/validate.py

# Target-only checks without connecting to MariaDB
python3 scripts/mysql2pg/validate.py --skip-source-check
```

## Passwords: Wrap Legacy Hashes Before Anyone Logs In

**Why.** The legacy deployment stores passwords as unsalted 128-character lowercase hex SHA-512 digests (`Hash.hashPasswordLegacy`). The `containerised` application verifies BCrypt only (`tomcat-credential-handler` `BCryptCredentialHandler` and `PasswordHasher`), as migration `V0021` removed plain SHA-512 verification. Leaving legacy digests unwrapped locks out every migrated user.

**What.** If `--skip-post-sql` was used or you loaded `users` outside `migrate.py`, run `sql/wrap-legacy-password-hashes.sql` before letting users log in:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f sql/wrap-legacy-password-hashes.sql
```

It wraps each legacy SHA-512 hex digest as `{sha512}$2a$12$...` (BCrypt cost 12 of the legacy hex digest):

- Every migrated user can log in with their existing password with no email reset required.
- No unsalted digest remains stored in `starexec.users`.
- Already-wrapped and plain BCrypt hashes are untouched, so the script is idempotent.
- Subsequent password changes store standard BCrypt.

**Requirements.**
- The `pgcrypto` extension (created automatically by the SQL script if absent).
- BCrypt cost 12 takes roughly 0.25 seconds per migrated user.

**Verification.** Confirm that no unusable password hash remains:

```sql
SELECT id, email FROM starexec.users
WHERE password !~ '^\$2[aby]\$' AND password NOT LIKE '{sha512}$2%';
```

**Known charset limitation.** Legacy SHA-512 hashing used the legacy JVM's default charset, whereas `containerised` hashes passwords as UTF-8. ASCII passwords always match; non-ASCII passwords match only if the legacy JVM's `file.encoding` / OS locale was UTF-8.

**Automated test coverage:**
- `PasswordHasherTests` and `BCryptCredentialHandlerTest` verify Java/`pgcrypto` parity.
- `LegacyPasswordWrappingSqlTest` runs the SQL wrapping script against PostgreSQL.
- `scripts/mysql2pg/test_manifest.py`, `test_migrate.py`, and `test_validate.py` verify table planning, row transformation, sequence checks, and validation logic (`python3 -m pytest -q scripts/`).
