package org.starexec.util;

import org.junit.Test;
import org.starexec.constants.R;
import org.starexec.data.database.Solvers;
import org.starexec.servlets.ProcessorManager;
import org.starexec.servlets.UploadBenchmark;
import org.starexec.servlets.UploadJobXML;
import org.starexec.servlets.UploadSolver;
import org.starexec.servlets.UploadSpaceXML;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Uploads and copies name the directories they create with {@link Util#pathDate()}. It replaced
 * SimpleDateFormat instances that request threads shared, which the SimpleDateFormat Javadoc
 * rules out ("Date formats are not synchronized"): a request could get a name mixing its own time
 * with another request's. The names themselves must not change.
 */
public class PathDateTest {

	/** Times that exercise every field: midnight, single digits, a leap day, the last millisecond. */
	private static final LocalDateTime[] TIMES = {
			LocalDateTime.of(2026, 10, 18, 0, 5, 9, 17_000_000),
			LocalDateTime.of(2026, 1, 2, 3, 4, 5, 6_000_000),
			LocalDateTime.of(2028, 2, 29, 12, 0, 0, 0),
			LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000),
			LocalDateTime.of(2026, 7, 4, 13, 30, 0, 123_456_789),
	};

	@Test
	public void aNameIsTheLocalTimeToTheMillisecond() {
		assertEquals("20260102-03.04.05.006", Util.pathDate(TIMES[1]));
	}

	/** The pattern's hour runs from 1 to 24, as it did with SimpleDateFormat. */
	@Test
	public void theHourAfterMidnightIsTwentyFour() {
		assertEquals("20261018-24.05.09.017", Util.pathDate(TIMES[0]));
	}

	@Test
	public void finerPrecisionIsCutToTheMillisecond() {
		assertEquals("20260704-13.30.00.123", Util.pathDate(TIMES[4]));
	}

	/**
	 * In the server's en_US locale, the SimpleDateFormat this replaces gives the same name for
	 * each of these times. Not for every time: before the Gregorian calendar began in 1582 it
	 * counts Julian dates and DateTimeFormatter does not, but directory names only ever carry
	 * the current time.
	 */
	@Test
	public void namesAreTheSameAsBefore() {
		for (LocalDateTime time : TIMES) {
			DateFormat before = new SimpleDateFormat(R.PATH_DATE_FORMAT, Locale.US);
			before.setTimeZone(TimeZone.getTimeZone("UTC"));
			String expected = before.format(Date.from(time.toInstant(ZoneOffset.UTC)));
			assertEquals(time.toString(), expected, Util.pathDate(time));
		}
	}

	/** The upload cleanup reads these names back, and must accept every one. */
	@Test
	public void theUploadCleanupAcceptsTheNames() {
		for (LocalDateTime time : TIMES) {
			assertTrue(time.toString(), UploadArtifactPathGuard.isUploadDirectoryName(Util.pathDate(time)));
		}
		assertTrue(UploadArtifactPathGuard.isUploadDirectoryName(Util.pathDate()));
	}

	/**
	 * Threads formatting at the same time each get their own time. The expected names are made
	 * one at a time first, so only the concurrent formatting is under test. A shared
	 * SimpleDateFormat fails this, because the threads overwrite its calendar mid-format.
	 */
	@Test
	public void threadsFormattingTogetherEachGetTheirOwnTime() throws Exception {
		int threads = 8;
		int perThread = 5_000;
		List<List<LocalDateTime>> times = new ArrayList<>();
		List<List<String>> expected = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			List<LocalDateTime> mine = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int i = 0; i < perThread; i++) {
				LocalDateTime time = TIMES[(t + i) % TIMES.length].plusDays(t).plusSeconds(i).plusNanos(i * 1_000_000L);
				mine.add(time);
				names.add(Util.pathDate(time));
			}
			times.add(mine);
			expected.add(names);
		}

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<String> allWrong = new ArrayList<>();
		boolean stopped;
		try {
			List<Future<List<String>>> results = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				List<LocalDateTime> mine = times.get(t);
				List<String> names = expected.get(t);
				results.add(pool.submit(() -> {
					start.await();
					List<String> wrong = new ArrayList<>();
					// Stops when interrupted, so that shutdownNow ends a failed run.
					for (int i = 0; i < mine.size() && !Thread.currentThread().isInterrupted(); i++) {
						String name = Util.pathDate(mine.get(i));
						if (!name.equals(names.get(i))) {
							wrong.add(mine.get(i) + " gave " + name + ", not " + names.get(i));
						}
					}
					return wrong;
				}));
			}
			start.countDown();
			for (Future<List<String>> result : results) {
				allWrong.addAll(result.get(60, TimeUnit.SECONDS));
			}
		} finally {
			// Interrupts the workers, which then stop before their next name, and waits for them,
			// so that a failed run leaves no thread formatting while the next test runs.
			pool.shutdownNow();
			stopped = pool.awaitTermination(60, TimeUnit.SECONDS);
		}
		// After the finally block, so that a worker's own failure is the one reported.
		assertTrue("the formatting threads did not stop within 60 seconds", stopped);
		assertEquals(allWrong.isEmpty() ? "" : allWrong.get(0), 0, allWrong.size());
	}

	/**
	 * The classes that name directories, Util included, keep no DateFormat of their own. One held
	 * in a field is shared by every request the class serves.
	 */
	@Test
	public void noClassThatNamesDirectoriesKeepsADateFormat() {
		Class<?>[] namers = {
				Util.class, Solvers.class, ProcessorManager.class, UploadJobXML.class, UploadSolver.class,
				UploadSpaceXML.class, UploadBenchmark.class,
		};
		for (Class<?> namer : namers) {
			for (Field field : namer.getDeclaredFields()) {
				assertFalse(namer.getSimpleName() + "." + field.getName(),
						DateFormat.class.isAssignableFrom(field.getType()));
			}
		}
	}
}
