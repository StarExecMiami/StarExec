-- Add password algorithm tracking columns to users table
-- This migration supports the transition from SHA-512 to BCrypt password hashing

ALTER TABLE users 
ADD COLUMN IF NOT EXISTS password_algorithm VARCHAR(10) DEFAULT 'SHA512' NOT NULL,
ADD COLUMN IF NOT EXISTS password_migrated_at TIMESTAMP;

-- Add index for monitoring migration progress
CREATE INDEX IF NOT EXISTS idx_users_password_algorithm ON users(password_algorithm);

-- Add comment to document the column
COMMENT ON COLUMN users.password_algorithm IS 'Password hashing algorithm: SHA512 (legacy) or BCRYPT (secure)';
COMMENT ON COLUMN users.password_migrated_at IS 'Timestamp when password was migrated from SHA512 to BCrypt';
