package org.starexec.servlets;

import org.junit.Test;

import javax.servlet.ServletContextEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class UploadJobWorkerStartupTests {

    @Test
    public void contextInitializedRunsStartupPhasesInOrder() {
        List<String> phases = new ArrayList<>();

        UploadJobWorker worker = new UploadJobWorker() {
            @Override
            void reconcileStartupState() {
                phases.add("reconcile");
            }

            @Override
            void cleanupStartupArtifacts() {
                phases.add("cleanup");
            }

            @Override
            void startWorkerInfrastructure() {
                phases.add("start");
            }
        };

        worker.contextInitialized((ServletContextEvent) null);

        assertEquals(Arrays.asList("reconcile", "cleanup", "start"), phases);
    }
}
