-- Wrap the password hashes imported from the legacy StarExec deployment (se-m1).
--
-- WHEN: once, right after the legacy users table has been loaded into this database, and
-- before any migrated user is allowed to log in. Re-running it is harmless.
--
-- WHY: the legacy deployment stores each password as the unsalted SHA-512 hex digest of the
-- password (128 lowercase hex characters). This application verifies BCrypt only, so those
-- users could not log in, and the reset-by-email fallback is not available everywhere. Rather
-- than keep the weak digests, each one is wrapped in BCrypt (cost 12) and marked {sha512}:
--
--     stored = '{sha512}' || bcrypt(legacy_sha512_hex)
--
-- Login then computes SHA-512 of the password typed and checks it against that BCrypt hash
-- (tomcat-credential-handler BCryptCredentialHandler, and PasswordHasher in the application).
-- No unsalted digest remains, no user has to do anything, and no email is needed.
--
-- WHAT IT TOUCHES: only values that are exactly a legacy digest. BCrypt hashes, values already
-- wrapped, and anything else are left as they are. A user whose stored value is not a legacy
-- digest and not BCrypt (for example a truncated import) still cannot log in; find them with
--     SELECT id, email FROM starexec.users
--     WHERE password !~ '^\$2[aby]\$' AND password NOT LIKE '{sha512}$2%';
--
-- REQUIRES: the pgcrypto extension (crypt, gen_salt). Creating it needs a role allowed to
-- create extensions; on the bundled PostgreSQL container the application role is.
--
-- COST: BCrypt at cost 12 takes about a quarter of a second per row on current hardware.
--
-- NOTE ON CHARACTER SETS: the legacy code hashed the password bytes in the server's default
-- charset; verification here uses UTF-8. They agree for every ASCII password. A user with a
-- non-ASCII password verifies only if the legacy server's default charset was UTF-8.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE starexec.users
SET password = '{sha512}' || crypt(password, gen_salt('bf', 12))
WHERE password ~ '^[0-9a-f]{128}$';
