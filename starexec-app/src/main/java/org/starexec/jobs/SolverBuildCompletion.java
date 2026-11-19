package org.starexec.jobs;

import org.starexec.backend.storage.BuildArtifactStore;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.ExecutionStorageLeases;
import org.starexec.data.database.SolverBuildArtifacts;
import org.starexec.logger.StarLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Periodic application of stopped managed outcomes; one unresolved build cannot block another. */
public final class SolverBuildCompletion {
    private static final StarLogger LOG=StarLogger.getLogger(SolverBuildCompletion.class);
    private static final Set<Long> reported=ConcurrentHashMap.newKeySet();
    private SolverBuildCompletion() { }

    public static void recordFinishedBuilds() {
        String configured=EnvironmentConfig.getBuildArtifactDirectory();
        if (configured==null || configured.isBlank()) return;
        try {
            var roots=new ArrayList<Path>();
            roots.add(Path.of(R.STAREXEC_DATA_DIR));
            roots.add(Path.of(EnvironmentConfig.getJobOutputDirectory()));
            roots.add(Path.of(EnvironmentConfig.getJobLogDirectory()));
            var excluded=new ArrayList<>(roots);
            excluded.addAll(ExecutionStorageLeases.registeredRootPaths());
            var store=BuildArtifactStore.configured(excluded);
            for (var artifact:SolverBuildArtifacts.stoppedLeasedBuilds()) {
                try {
                    if (ExecutionStorageLeases.recordBuildOutcomeAndClean(artifact,store,roots)) reported.remove(artifact.id());
                } catch (Exception unresolved) {
                    if (reported.add(artifact.id())) LOG.warn("Managed build outcome remains unresolved for artifact "+artifact.id(),unresolved);
                }
            }
        } catch (Exception unavailable) {
            LOG.warn("Managed build publication storage or catalog is unavailable",unavailable);
        }
    }
}
