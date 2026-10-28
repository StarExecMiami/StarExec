package org.starexec.test.integration.servlets;


import org.starexec.data.to.User;
import org.starexec.servlets.UploadBenchmark;
import org.starexec.test.integration.StarexecTest;
import org.starexec.test.integration.TestSequence;
import org.starexec.test.resources.ResourceLoader;
import org.starexec.util.Util;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BenchmarkUploaderTests extends TestSequence {

    static class BenchmarkUploaderWrapper {
        public File getDirectoryForBenchmarkUpload(int userId, String name) throws IOException {
            return UploadBenchmark.getDirectoryForBenchmarkUpload(userId, name);
        }
    }

    public String getTestName() {
        return "BenchmarkUploaderTests";
    }

    @StarexecTest
    public void getBenchmarkDirectoryTest() {
        try (ResourceLoader loader = new ResourceLoader()) {
            User user = loader.loadUserIntoDatabase();
            File dir = UploadBenchmark.getDirectoryForBenchmarkUpload(user.getId(), null);

            assertTrue(dir.delete(), "Directory could not be deleted.");

        } catch (IOException e) {
            fail("Directory was not created: " + Util.getStackTrace(e));
        }
    }

    public void setup() {

    }

    public void teardown() {

    }

}
