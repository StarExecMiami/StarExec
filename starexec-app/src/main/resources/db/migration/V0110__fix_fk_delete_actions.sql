-- Make foreign-key delete actions explicit for constraints whose current
-- definitions either omit ON DELETE behavior or use implicit NO ACTION.
--
-- Historical snapshot columns such as job_pairs.bench_id and
-- jobpair_stage_data.{solver_id,config_id,job_space_id} intentionally remain
-- unconstrained so completed job history survives primitive deletion/recycling.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM starexec.syntax WHERE id = 1) THEN
        RAISE EXCEPTION 'Expected syntax id=1 Plain Text row is missing';
    END IF;
END $$;

ALTER TABLE starexec.processors
    DROP CONSTRAINT processors_syntax,
    ADD CONSTRAINT processors_syntax
        FOREIGN KEY (syntax_id)
        REFERENCES starexec.syntax(id)
        ON DELETE SET DEFAULT
        NOT VALID;

ALTER TABLE starexec.analytics_historical
    DROP CONSTRAINT id_assoc,
    ADD CONSTRAINT id_assoc
        FOREIGN KEY (event_id)
        REFERENCES starexec.analytics_events(event_id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE starexec.analytics_users
    DROP CONSTRAINT analytics_users_to_event,
    ADD CONSTRAINT analytics_users_to_event
        FOREIGN KEY (event_id)
        REFERENCES starexec.analytics_events(event_id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE starexec.analytics_users
    DROP CONSTRAINT analytics_users_to_users,
    ADD CONSTRAINT analytics_users_to_users
        FOREIGN KEY (user_id)
        REFERENCES starexec.users(id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE starexec.runscript_errors
    DROP CONSTRAINT runscript_errors_job_pair_id,
    ADD CONSTRAINT runscript_errors_job_pair_id
        FOREIGN KEY (job_pair_id)
        REFERENCES starexec.job_pairs(id)
        ON DELETE CASCADE
        NOT VALID;

ALTER TABLE starexec.logins
    DROP CONSTRAINT logins_user_id,
    ADD CONSTRAINT logins_user_id
        FOREIGN KEY (user_id)
        REFERENCES starexec.users(id)
        ON DELETE RESTRICT
        NOT VALID;

ALTER TABLE starexec.job_pairs
    DROP CONSTRAINT job_pairs_node_id,
    ADD CONSTRAINT job_pairs_node_id
        FOREIGN KEY (node_id)
        REFERENCES starexec.nodes(id)
        ON DELETE SET NULL
        NOT VALID;

COMMENT ON COLUMN starexec.job_pairs.bench_id IS
    'Historical snapshot reference only; intentionally no FK so job pair history survives benchmark deletion/recycling.';

COMMENT ON COLUMN starexec.jobpair_stage_data.solver_id IS
    'Historical snapshot reference only; intentionally no FK so stage data survives solver deletion/recycling.';

COMMENT ON COLUMN starexec.jobpair_stage_data.config_id IS
    'Historical snapshot reference only; intentionally no FK so stage data survives configuration deletion/recycling.';

COMMENT ON COLUMN starexec.jobpair_stage_data.job_space_id IS
    'Historical snapshot reference only; intentionally no FK so stage data survives space/job-space lifecycle changes.';
