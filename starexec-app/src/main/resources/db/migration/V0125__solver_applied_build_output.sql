-- The applied outcome is distinct from an observed or rejected build attempt.
ALTER TABLE starexec.solvers
    ADD COLUMN build_output_artifact_id BIGINT,
    ADD CONSTRAINT solvers_build_output_artifact_fk FOREIGN KEY (build_output_artifact_id)
        REFERENCES starexec.solver_build_artifacts(id);
