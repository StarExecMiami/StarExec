package org.starexec.test.resources;

import org.starexec.data.to.CommunityRequest;
import org.starexec.data.to.Configuration;
import org.starexec.data.to.DefaultSettings;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.pipelines.SolverPipeline;
import org.starexec.data.to.Processor;
import org.starexec.data.to.Queue;
import org.starexec.data.to.Solver;
import org.starexec.data.to.Space;
import org.starexec.data.to.User;
import org.starexec.data.to.enums.ProcessorType;
import org.openqa.selenium.WebDriver;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public interface IResourceLoader extends AutoCloseable {
        void deleteAllPrimitives();

        @Override
        void close();

        String getResourcePath();

        File getResource(String name);

        File getDownloadDirectory();

        // Processors
        Processor loadBenchProcessorIntoDatabase(int communityId);

        Processor loadProcessorIntoDatabase(ProcessorType type, int communityId);

        Processor loadProcessorIntoDatabase(String fileName, ProcessorType type, int communityId);

        // Jobs
        Job loadJobIntoDatabase(int spaceId, int userId, int solverId, List<Integer> benchmarkIds);

        Job loadJobIntoDatabase(int spaceId, int userId, int preProcessorId, int postProcessorId, int solverId,
                        List<Integer> benchmarkIds, int cpuTimeout, int wallclockTimeout, int memory);

        Job loadJobIntoDatabase(int spaceId, int userId, int preProcessorId, int postProcessorId,
                        List<Integer> solverIds,
                        List<Integer> benchmarkIds, int cpuTimeout, int wallclockTimeout, int memory);

        Job loadJobHierarchyIntoDatabase(int rootSpaceId, int userId, int preProcessorId, int postProcessorId);

        // Settings
        DefaultSettings loadDefaultSettingsProfileIntoDatabase(int userId);

        DefaultSettings loadDefaultSettingsProfileIntoDatabaseWithDefaultBenchmarks(int userId, List<Integer> benchIds)
                        throws SQLException;

        // Configurations
        Configuration loadConfigurationFileIntoDatabase(String fileName, int solverId);

        Configuration loadConfigurationIntoDatabase(String contents, int solverId);

        List<String> getTestConfigDirectory() throws IOException;

        // Benchmarks
        List<Integer> loadBenchmarksIntoDatabase(int parentSpaceId, int userId);

        List<Integer> loadBenchmarksIntoDatabase(String archiveName, int parentSpaceId, int userId);

        // Solvers
        Solver loadSolverIntoDatabase(int parentSpaceId, int userId);

        Solver loadSolverIntoDatabase(String archiveName, int parentSpaceId, int userId);

        SolverPipeline loadPipelineIntoDatabase(int userId, List<Configuration> configs);

        // Spaces
        Space loadSpaceIntoDatabase(int userId, int parentSpaceId, String name);

        Space loadSpaceIntoDatabase(int userId, int parentSpaceId);

        // Users
        User loadUserIntoDatabase();

        User loadUserIntoDatabase(String password);

        User loadAdminIntoDatabase();

        User loadDevIntoDatabase();

        User loadUserIntoDatabase(String password, String role);

        User loadUserIntoDatabase(String fname, String lname, String email, String password, String institution,
                        String role);

        // Queues
        Queue loadQueueIntoDatabase(int wallTimeout, int cpuTimeout);

        // Requests
        CommunityRequest loadCommunityRequestIntoDatabase(int userId, int commId);

        // WebDriver
        WebDriver getWebDriver(String email, String password, boolean visible);

        WebDriver getWebDriver(String email, String password);

        WebDriver getFirefoxDriver(String email, String password);

        // Utils
        void writeFakeJobPairOutput(JobPair pair);

        File getBasicTestXMLFile(int configId, int benchId) throws IOException;

        File getJoblineTestXMLFile(int configId1, int configId2, int benchId1, int benchId2) throws IOException;
}
