package org.starexec.test.junit.constants;

import org.junit.Assert;
import org.junit.Test;
import org.starexec.constants.PaginationQueries;
import org.starexec.constants.R;

import java.io.IOException;

public class PaginationQueriesTest {

    @Test
    public void testLoadPaginationQueriesFallback() throws IOException {
        // Set R.CONFIG_PATH to a non-existent directory to force fallback
        String originalConfigPath = R.CONFIG_PATH;
        R.CONFIG_PATH = "/tmp/non-existent-config-starexec";

        try {
            PaginationQueries.loadPaginationQueries();

            // Verify that at least one query is loaded (not empty)
            Assert.assertNotNull("Query should not be null", PaginationQueries.GET_PAIRS_IN_SPACE_QUERY);
            Assert.assertFalse("Query should not be empty", PaginationQueries.GET_PAIRS_IN_SPACE_QUERY.isEmpty());

            // Verify Postgres normalization happened
            Assert.assertTrue("Query should be normalized for Postgres",
                    PaginationQueries.GET_SOLVERS_IN_SPACE_QUERY.contains("|| COALESCE(:query, '')::text ||"));

        } finally {
            // Restore original path
            R.CONFIG_PATH = originalConfigPath;
        }
    }
}
