-- Schema Change: Set default version to 1.1 if not set
-- If major and minor version are not set, set them both to 1

-- This exists due to an error, where these fields were added to the DB but not
-- set, introduced in commit cf33c94d3ca0122897ca9c22bcf2a10804d908f2

UPDATE system_flags 
SET major_version=1, minor_version=1 
WHERE major_version IS NULL AND minor_version IS NULL;