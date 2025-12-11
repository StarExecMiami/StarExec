-- Add migration tracking column
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS last_login_algorithm VARCHAR(10);

-- Add index for performance
CREATE INDEX IF NOT EXISTS idx_users_migration_status 
ON users(password_algorithm, last_login_algorithm);

-- Add comment
COMMENT ON COLUMN users.last_login_algorithm IS 'Algorithm user last authenticated with (for migration tracking)';
