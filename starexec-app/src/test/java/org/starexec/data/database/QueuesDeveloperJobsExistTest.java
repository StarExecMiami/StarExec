package org.starexec.data.database;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.to.Queue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class QueuesDeveloperJobsExistTest {

    @Test
    public void findsDeveloperJobsInALaterActiveQueue() throws Exception {
        assertDeveloperJobsExist(Arrays.asList(queue(1), queue(2)), false, true);
    }

    @Test
    public void returnsFalseWhenNoActiveQueueHasDeveloperJobs() throws Exception {
        assertDeveloperJobsExist(Arrays.asList(queue(1), queue(2)), false, false);
    }

    private static Queue queue(int id) {
        Queue queue = mock(Queue.class);
        when(queue.getId()).thenReturn(id);
        return queue;
    }

    private static void assertDeveloperJobsExist(
            java.util.List<Queue> queues,
            boolean firstQueueHasJobs,
            boolean secondQueueHasJobs) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet first = mock(ResultSet.class);
        ResultSet second = mock(ResultSet.class);
        when(connection.prepareStatement(
                "SELECT * FROM starexec.GetPendingDeveloperJobs(?)"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(first, second);
        when(first.next()).thenReturn(firstQueueHasJobs);
        when(second.next()).thenReturn(secondQueueHasJobs);

        try (MockedStatic<Queues> queuesMock = Mockito.mockStatic(Queues.class);
             MockedStatic<Common> commonMock = Mockito.mockStatic(Common.class)) {
            queuesMock.when(Queues::developerJobsExist).thenCallRealMethod();
            queuesMock.when(Queues::getAllActive).thenReturn(queues);
            commonMock.when(Common::getConnection).thenReturn(connection);

            boolean result = Queues.developerJobsExist();

            if (secondQueueHasJobs) {
                assertTrue(result);
            } else {
                assertFalse(result);
            }
            if (queues.size() > 1 && !firstQueueHasJobs) {
                verify(statement).setInt(1, queues.get(1).getId());
            }
        }
    }
}
