-- V20251203__bcrypt_only_passwords.sql
-- Remove legacy SHA-512 password support and use BCrypt exclusively
-- This migration:
-- 1. Removes the last_login_algorithm column (no longer needed)
-- 2. Removes the migration_stats_view (no longer needed)
-- 3. Updates default user passwords to BCrypt format

-- Remove migration tracking artifacts
DROP VIEW IF EXISTS starexec.migration_stats;
ALTER TABLE starexec.users DROP COLUMN IF EXISTS last_login_algorithm;

-- Note: Existing SHA-512 passwords (128 hex chars) will NOT work after this migration.
-- Users with SHA-512 passwords must reset their password using the forgot password flow.

-- BCrypt hash for 'admin' password (generated with work factor 12)
-- Generated using: BCrypt.hashpw("admin", BCrypt.gensalt(12))
UPDATE starexec.users 
SET password = '$2a$12$A4AmJEY.PTCaK/4AN8RU7evjxcbP9c6K25h17BtCZLGmmBln4ZJP2'
WHERE email = 'admin';

-- BCrypt hash for 'public' password
-- Generated using: BCrypt.hashpw("public", BCrypt.gensalt(12))
UPDATE starexec.users
SET password = '$2a$12$PtxXbL4q86Yrk40WDnTmBeKhf1MNVmME8iLg99L7ULa4HMl7Btaty'
WHERE email = 'public';

-- Verify the update
DO $$
BEGIN
    RAISE NOTICE 'Password migration complete. Users with old SHA-512 passwords must reset via forgot password.';
END $$;
