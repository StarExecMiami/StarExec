package org.starexec.data.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.starexec.data.to.SolverBuildStopReceipt;

class SolverBuildStopPersistenceTest {
    private SolverBuildStopReceipt receipt() {
        var facts = new JsonObject(); facts.addProperty("status", "exited");
        return SolverBuildStopReceipt.observed("podman", 5, 42, 1, "engine", "container", Instant.now(), facts);
    }

    @Test void aBorrowedTransactionCannotBeMistakenForACommittedReceipt() throws Exception {
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getBoolean(1)).thenReturn(true);
        try (var common = mockStatic(Common.class, CALLS_REAL_METHODS)) {
            common.when(Common::getConnection).thenReturn(connection);
            assertThrows(SQLException.class, () -> SolverBuildArtifacts.recordStopReceipt(42, 1, UUID.randomUUID(), "engine", receipt()));
            verify(connection, never()).prepareStatement(anyString()); verify(connection, never()).commit();
        }
    }

    @Test void theStandaloneReceiptCommitsBeforeReturningAndClosing() throws Exception {
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getBoolean(1)).thenReturn(true);
        try (var common = mockStatic(Common.class, CALLS_REAL_METHODS)) {
            common.when(Common::getConnection).thenReturn(connection);
            assertTrue(SolverBuildArtifacts.recordStopReceipt(42, 1, UUID.randomUUID(), "engine", receipt()));
            var order = inOrder(connection, statement);
            order.verify(connection).setAutoCommit(false); order.verify(statement).executeQuery();
            order.verify(connection).commit(); order.verify(connection).setAutoCommit(true); order.verify(connection).close();
        }
    }
}
