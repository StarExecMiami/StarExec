package org.starexec.data.database;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockStatic;

import java.sql.SQLException;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * What {@link Queues#countPendingPairs} answers when it cannot ask the database.
 *
 * <p>The dispatcher's line for a queue with no nodes prints this count. A count that failed must
 * not read as "0 pair(s) waiting": that is the wrong number the count replaced.
 */
public class QueuesPendingPairsTest {

    @Test
    public void aCountThatCannotReachTheDatabaseIsMinusOneNotZero() {
        try (MockedStatic<Common> common = mockStatic(Common.class)) {
            common.when(Common::getConnection).thenThrow(new SQLException("the database is down"));
            assertEquals(-1, Queues.countPendingPairs(7));
        }
    }
}
