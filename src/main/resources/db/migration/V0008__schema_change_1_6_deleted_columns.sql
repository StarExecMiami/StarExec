-- Schema Change: Add deleted columns to configurations and solvers
-- Modify table configurations by adding a "deleted" column
-- Modify table solvers by adding a "config_deleted" column
-- Column jobs.deleted is of type INT and not BOOL, so to be consistent I will do the same here
-- Adding the column to configurations resolves solvers being misrepresented when displayed in job spaces
-- in the case of their configurations being deleted (currently causes query to return empty set)
-- Adding the column to solvers allows for link modification in the solvers summary table in the 
-- job space; this is necessary to improve user experience and not have dead links to a 404 page in the
-- case of deleted configurations
-- Author: Alexander Brown

-- Update the minor version flag
UPDATE system_flags SET minor_version=6;

-- Add columns only if they don't already exist (make migration idempotent / safe for DBs
-- where earlier edits already added these columns).
DO $$
BEGIN
	IF NOT EXISTS (
		SELECT 1 FROM information_schema.columns
		WHERE table_schema = current_schema()
		  AND table_name = 'configurations'
		  AND column_name = 'deleted'
	) THEN
		ALTER TABLE configurations ADD COLUMN deleted INT DEFAULT 0;
	END IF;
	IF NOT EXISTS (
		SELECT 1 FROM information_schema.columns
		WHERE table_schema = current_schema()
		  AND table_name = 'solvers'
		  AND column_name = 'config_deleted'
	) THEN
		ALTER TABLE solvers ADD COLUMN config_deleted INT DEFAULT 0;
	END IF;
END$$;