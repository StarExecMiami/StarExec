package org.starexec.test.junit.constants;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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
            Assertions.assertNotNull(PaginationQueries.GET_PAIRS_IN_SPACE_QUERY, "Query should not be null");
            Assertions.assertFalse(PaginationQueries.GET_PAIRS_IN_SPACE_QUERY.isEmpty(), "Query should not be empty");

            // Verify Postgres normalization happened
            Assertions.assertTrue(PaginationQueries.GET_SOLVERS_IN_SPACE_QUERY.contains("|| COALESCE(:query, '')::text ||"),
                    "Query should be normalized for Postgres");

        } finally {
            // Restore original path
            R.CONFIG_PATH = originalConfigPath;
        }
    }
}
