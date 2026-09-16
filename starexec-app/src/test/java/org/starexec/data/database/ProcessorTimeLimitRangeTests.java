package org.starexec.data.database;

import org.junit.Test;
import java.sql.Connection;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A processor time limit must fit the column that stores it.
 *
 * <p>{@code processors.time_limit} is {@code SMALLINT} (V0001:143) and the routine that writes
 * it takes a {@code SMALLINT} parameter, but {@code updateTimeLimit} accepted an {@code int}
 * and handed it to {@code setShort} through a cast. {@code RESTServices.editProcessor} parses
 * the value straight from a request parameter and range-checks nothing, so a submitted
 * {@code timelimit} of 40000 became {@code (short) 40000}, which is -25536: a processor
 * silently acquired a negative time limit, and the request reported success.
 *
 * <p>Truncation is the worst available outcome for a scientific system -- it neither stores
 * what was asked for nor says it did not -- so the value is now refused, and refused at the
 * database boundary where the constraint actually lives rather than only at the one caller.
 */
public class ProcessorTimeLimitRangeTests {

	@Test
	public void theColumnsWholeRangeIsStorable() {
		assertTrue("1 second is the smallest useful limit", Processors.isStorableTimeLimit(1));
		assertTrue("the default must be storable",
				Processors.isStorableTimeLimit(org.starexec.constants.R.PROCESSOR_TIME_LIMIT));
		assertTrue("SMALLINT's maximum must be storable",
				Processors.isStorableTimeLimit(Short.MAX_VALUE));
		assertTrue(Processors.isStorableTimeLimit(Short.MAX_VALUE - 1));
	}

	@Test
	public void aValueTooLargeForTheColumnIsRefused() {
		// (short) 32768 is -32768 and (short) 40000 is -25536: both stored a negative limit.
		assertFalse("one past SMALLINT must be refused, not wrapped",
				Processors.isStorableTimeLimit(Short.MAX_VALUE + 1));
		assertFalse(Processors.isStorableTimeLimit(40000));
		assertFalse(Processors.isStorableTimeLimit(Integer.MAX_VALUE));
	}

	@Test
	public void aNonPositiveValueIsRefused() {
		assertFalse("zero is not a time limit", Processors.isStorableTimeLimit(0));
		assertFalse(Processors.isStorableTimeLimit(-1));
		assertFalse(Processors.isStorableTimeLimit(Integer.MIN_VALUE));
	}

	/**
	 * A value that cannot be stored must never reach the routine that stores it.
	 *
	 * <p>Asserting only that the call returns false would prove nothing: with no database
	 * present it returns false either way, so the test would pass over an unguarded method.
	 * The assertion is therefore that {@code UpdateProcessorTimeLimit} is never prepared.
	 *
	 * <p>It is specifically not "no connection is opened". {@code StarLogger} writes every WARN
	 * and ERROR to the database through {@code ErrorLogs.add}, so the guard's own warning opens
	 * one; that is incidental here, and asserting against it would couple this test to how
	 * logging happens to work.
	 */
	@Test
	public void anOutOfRangeUpdateNeverReachesTheRoutine() throws Exception {
		Connection connection = Mockito.mock(Connection.class, Mockito.RETURNS_DEEP_STUBS);
		try (MockedStatic<Common> common = Mockito.mockStatic(Common.class)) {
			common.when(() -> Common.getConnection()).thenReturn(connection);

			assertFalse("an unstorable time limit must be refused",
					Processors.updateTimeLimit(1, 40000));

			Mockito.verify(connection, Mockito.never())
					.prepareStatement(Mockito.contains("UpdateProcessorTimeLimit"));
		}
	}
}
