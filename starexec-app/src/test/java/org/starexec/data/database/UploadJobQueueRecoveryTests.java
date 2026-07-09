package org.starexec.data.database;

import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UploadJobQueueRecoveryTests {

    @Test
    public void reconcileStaleProcessingJobsInTransactionFinalizesCancelledAndFailedRows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement cancelStatement = mock(PreparedStatement.class);
        PreparedStatement failStatement = mock(PreparedStatement.class);
        PreparedStatement retentionStatement = mock(PreparedStatement.class);

        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(cancelStatement, failStatement, retentionStatement);
        when(cancelStatement.executeUpdate()).thenReturn(1);
        when(failStatement.executeUpdate()).thenReturn(2);

        UploadJobQueue.ReconciliationResult result = UploadJobQueue.reconcileStaleProcessingJobsInTransaction(connection);

        assertEquals(1, result.getCancelledCount());
        assertEquals(2, result.getFailedCount());
        verify(cancelStatement).executeUpdate();
        verify(failStatement).setString(1,
            "Upload processing was interrupted by application restart before completion. Please retry the upload job.");
        verify(failStatement).setString(2,
            "Upload processing was interrupted by application restart before completion. Please retry the upload job.");
        verify(failStatement).executeUpdate();
        verify(retentionStatement).setInt(1, 168);
        verify(retentionStatement).executeUpdate();
    }
}
