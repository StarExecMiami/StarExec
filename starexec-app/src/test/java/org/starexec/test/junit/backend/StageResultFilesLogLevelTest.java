package org.starexec.test.junit.backend;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;
import org.starexec.backend.StageAttributeFiles;
import org.starexec.backend.StageStatsFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * At which level a finished stage's missing per-stage file is reported.
 *
 * <p>StarLogger stores every WARN as an {@code error_logs} row, and the error log report mails those
 * rows to the administrators. A stage records attributes only from a post-processor that succeeded
 * (docs/BACKENDS.md), so a stage without a post-processor never has an attributes file: that is
 * the normal outcome, and it was a WARN for every finished stage of every job without a
 * post-processor. A finished stage without measurements has lost them, and that stays a WARN.
 */
public class StageResultFilesLogLevelTest {

	private static final int PAIR = 77;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Logger logger;
	private Level previous;
	private ListAppender<ILoggingEvent> appender;

	@Before
	public void captureLog() {
		logger = (Logger) LoggerFactory.getLogger("org.starexec.backend.StageResultFiles");
		previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@After
	public void releaseLog() {
		logger.detachAppender(appender);
		appender.stop();
		logger.setLevel(previous);
	}

	private Path output() throws Exception {
		return folder.newFolder().toPath();
	}

	private static Path declare(Path out, String directory) throws Exception {
		return Files.createDirectories(out.resolve(directory));
	}

	private List<String> all() {
		return appender.list.stream()
				.map(e -> e.getLevel() + " " + e.getFormattedMessage())
				.collect(Collectors.toList());
	}

	private List<String> atOrAbove(Level level) {
		return appender.list.stream()
				.filter(e -> e.getLevel().isGreaterOrEqual(level))
				.map(e -> e.getLevel() + " " + e.getFormattedMessage())
				.collect(Collectors.toList());
	}

	private long count(Level level, String... parts) {
		return appender.list.stream()
				.filter(e -> e.getLevel() == level)
				.filter(e -> {
					for (String part : parts) {
						if (!e.getFormattedMessage().contains(part)) {
							return false;
						}
					}
					return true;
				})
				.count();
	}

	/** A single-stage pair of a job without a post-processor, at completion. */
	@Test
	public void aFinishedStageWithoutAPostProcessorIsNotAWarning() throws Exception {
		Path out = output();
		declare(out, "stage-attributes");

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(), 1, new Properties(), () -> Set.of(1));

		assertTrue("a stage without a file has no attributes", selected.isEmpty());
		assertEquals("nothing at WARN or above, which is stored in error_logs: " + all(),
				List.of(), atOrAbove(Level.WARN));
		assertEquals("one DEBUG line still names the pair, the stage and what it lacks: " + all(),
				1, count(Level.DEBUG, "Pair 77 stage 1 ", "attributes"));
	}

	/**
	 * A final stage that ran no post-processor after one that did: the first stage's attributes are
	 * recorded, and the second stage's missing file is the normal outcome too.
	 */
	@Test
	public void aFinalStageWithoutAPostProcessorIsNotAWarning() throws Exception {
		Path out = output();
		Files.writeString(declare(out, "stage-attributes").resolve("1.txt"), "starexec-result=Theorem\n");

		Map<Integer, Properties> selected = StageAttributeFiles.select(
				out, PAIR, Set.of(1), 2, new Properties(), () -> Set.of(1, 2));

		assertEquals(Set.of(1), selected.keySet());
		assertEquals("Theorem", selected.get(1).getProperty("starexec-result"));
		assertEquals("nothing at WARN or above: " + all(), List.of(), atOrAbove(Level.WARN));
		assertEquals("one DEBUG line for stage 2: " + all(),
				1, count(Level.DEBUG, "Pair 77 stage 2 ", "attributes"));
	}

	/** Every stage that ran publishes its measurements, so one without them has lost them. */
	@Test
	public void aFinishedStageWithoutMeasurementsIsStillAWarning() throws Exception {
		Path out = output();
		declare(out, "stage-stats");

		Map<Integer, StageStatsFiles.Stats> selected = StageStatsFiles.select(
				out, PAIR, Set.of(), 1, () -> Set.of(1));

		assertTrue(selected.isEmpty());
		assertEquals("the lost measurements are still a WARN: " + all(),
				1, count(Level.WARN, "Pair 77 stage 1 finished without a per-stage measurements file"));
	}

	/**
	 * While the pair runs, a finished stage's missing files are not reported at any level: a Local
	 * monitor sees that state on every poll until the pair finishes.
	 */
	@Test
	public void aRunningPairReportsNoMissingFile() throws Exception {
		Path out = output();
		declare(out, "stage-attributes");
		declare(out, "stage-stats");

		assertTrue(StageAttributeFiles.select(
				out, PAIR, Set.of(1), 0, new Properties(), () -> Set.of(1, 2)).isEmpty());
		assertTrue(StageStatsFiles.select(out, PAIR, Set.of(1), 0, () -> Set.of(1, 2)).isEmpty());

		assertEquals("no line while the pair runs: " + all(), List.of(), all());
	}
}
