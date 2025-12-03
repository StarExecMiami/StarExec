-- Create a view to track password migration progress
CREATE OR REPLACE VIEW migration_stats AS
SELECT
    COALESCE(password_algorithm, 'SHA512') as password_algorithm,
    COALESCE(last_login_algorithm, 'NONE') as last_login_algorithm,
    COUNT(*) as user_count
FROM users
GROUP BY password_algorithm, last_login_algorithm;
