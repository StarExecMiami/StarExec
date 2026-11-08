package org.starexec.data.database;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Catalog snapshots of published solver inputs for one current job-pair attempt. */
public final class PublishedSolverInputs {
    public record Input(SolverBuildArtifacts.Artifact artifact,SolverBuildArtifacts.OutputManifest manifest) { }
    private PublishedSolverInputs() { }

    public static List<Input> forPair(int pair,Integer attempt) throws SQLException {
        if (pair<1 || attempt==null || attempt<1) throw new SQLException("Published inputs require a current pair attempt");
        try (var connection=Common.getConnection()) {
            if (!Objects.equals(JobPairs.getCurrentAttemptNo(connection,pair),attempt)) throw new SQLException("Published input attempt changed");
            try (var query=connection.prepareStatement("SELECT a.*,s.id AS input_solver,s.user_id AS input_owner,s.path AS input_path,"
                    +"s.deleted AS input_deleted,s.recycled AS input_recycled FROM starexec.jobpair_stage_data sd"
                    +" JOIN starexec.solvers s ON s.id=sd.solver_id"
                    +" JOIN starexec.solver_build_artifacts a ON a.artifact_path=s.path"
                    +" WHERE sd.jobpair_id=? ORDER BY s.id,a.id")) {
                query.setInt(1,pair);
                var inputs=new LinkedHashMap<Long,Input>();
                try (var rows=query.executeQuery()) {
                    while (rows.next()) {
                        var artifact=SolverBuildArtifacts.artifact(rows);
                        if (!"PUBLISHED".equals(artifact.state()) || artifact.liveSolverId()==null
                                || artifact.liveSolverId()!=rows.getInt("input_solver")
                                || artifact.ownerId()!=rows.getInt("input_owner") || rows.getBoolean("input_deleted")
                                || rows.getBoolean("input_recycled") || artifact.solverPath()==null
                                || !Path.of(artifact.solverPath()).equals(Path.of(rows.getString("input_path")))
                                || rows.getString("manifest_payload")==null || rows.getString("manifest_sha256")==null) {
                            throw new SQLException("Solver input differs from its published catalog identity");
                        }
                        var input=new Input(artifact,new SolverBuildArtifacts.OutputManifest(rows.getString("manifest_payload"),
                                rows.getString("manifest_sha256"),rows.getLong("byte_size")));
                        var previous=inputs.putIfAbsent(artifact.id(),input);
                        if (previous!=null && !previous.equals(input)) throw new SQLException("Published solver input identity is ambiguous");
                    }
                }
                if (!Objects.equals(JobPairs.getCurrentAttemptNo(connection,pair),attempt)) throw new SQLException("Published input attempt changed during capture");
                return List.copyOf(inputs.values());
            }
        }
    }
}
