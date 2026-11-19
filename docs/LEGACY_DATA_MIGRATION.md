# Migrating data from the legacy deployment

The `containerised` line replaces the legacy StarExec deployment (branch `UMprod`, running on
se-m1). Its production data is to be moved into a `containerised` deployment (Quokka), which
then serves as staging until the legacy deployment is retired.

This document records the steps that the move requires and that differ from a plain copy.
It is not yet a complete runbook: there is no tool in this repository that copies the legacy
MariaDB schema into this PostgreSQL schema. Add each step here as it is built and verified.

## Passwords: wrap the legacy hashes before anyone logs in

**Why.** The legacy deployment stores every password as the unsalted SHA-512 hex digest of the
password: 128 lowercase hex characters, produced by `Hash.hashPasswordLegacy`. This application
verifies BCrypt only, both at login (`tomcat-credential-handler`, `BCryptCredentialHandler`)
and in the application (`PasswordHasher`). Migration `V0021` removed the earlier SHA-512 path and
expected users to reset their passwords by email. A copied users table therefore locks out every
migrated user, and resetting by email is not a fallback where outgoing mail cannot be delivered.

**What.** After the legacy `users` rows are loaded, and before migrated users log in, run:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f sql/wrap-legacy-password-hashes.sql
```

It replaces each legacy digest with `{sha512}` followed by a BCrypt (cost 12) hash of that
digest. Login recognises the `{sha512}` prefix, computes SHA-512 of the password typed and checks
that against the BCrypt hash. The result:

- every migrated user logs in with their existing password, with no action and no email;
- no unsalted digest remains in the database, and a leaked legacy digest is not a working password;
- BCrypt hashes, values already wrapped and anything else are untouched, so the script can be re-run;
- a password changed after the move is stored as plain BCrypt, as for any other user.

**Requirements.**
- The `pgcrypto` extension; the script creates it if it is absent.
- BCrypt at cost 12 takes about a quarter of a second per user.

**Check.** Before letting users in, confirm that no unusable value is left:

```sql
SELECT id, email FROM starexec.users
WHERE password !~ '^\$2[aby]\$' AND password NOT LIKE '{sha512}$2%';
```

**Known limit.** The legacy code hashed the password bytes in the server's default charset;
verification here uses UTF-8. They agree for every ASCII password. A user with a non-ASCII
password logs in only if se-m1's default charset was UTF-8. Check se-m1's locale (`locale`,
`file.encoding` of its JVM) before the move, to know whether any users are affected.

**Tested by.**
- `PasswordHasherTests` and `BCryptCredentialHandlerTest`: they verify hashes produced by
  `pgcrypto`, so the SQL and Java sides cannot drift.
- `LegacyPasswordWrappingSqlTest`: it runs the script itself against PostgreSQL.
