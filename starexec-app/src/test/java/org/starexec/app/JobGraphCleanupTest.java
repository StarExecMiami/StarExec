package org.starexec.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.starexec.constants.R;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * The scheduled job graph cleanup must sweep the directory Statistics writes graphs into: R.JOBGRAPH_FILE_DIR under
 * the web root. Pointed at R.JOBGRAPH_FILE_DIR alone ("/jobgraphs", at the filesystem root, which does not exist)
 * it never deleted anything, and every graph a user opened stayed on disk.
 */
public class JobGraphCleanupTest {
	@TempDir
	public File tmp;

	private String originalRoot;
	private File graphs;

	@BeforeEach
	public void useATemporaryWebRoot() throws Exception {
		originalRoot = R.STAREXEC_ROOT;
		File root = newFolder(tmp, "webapp");
		R.STAREXEC_ROOT = root.getAbsolutePath();
		graphs = new File(root, R.JOBGRAPH_FILE_DIR);
		assertTrue(graphs.mkdirs());
	}

	@AfterEach
	public void restoreTheWebRoot() {
		R.STAREXEC_ROOT = originalRoot;
	}

	@Test
	public void graphsOlderThanAWeekAreDeletedAndNewerOnesKept() throws Exception {
		File old = graph("old.png", 8);
		File recent = graph("recent.png", 1);

		scheduledTask("CLEAR_JOB_GRAPHS_TASK").run();

		assertFalse(old.exists(), "a graph older than seven days is deleted");
		assertTrue(recent.exists(), "a graph from yesterday is kept");
		assertTrue(graphs.isDirectory(), "the graph directory itself stays");
	}

	private File graph(String name, int daysOld) throws Exception {
		File f = new File(graphs, name);
		Files.write(f.toPath(), new byte[] {1});
		Files.setLastModifiedTime(f.toPath(), FileTime.from(Instant.now().minus(daysOld, ChronoUnit.DAYS)));
		return f;
	}

	/** The task exactly as PeriodicTasks schedules it. */
	private static Runnable scheduledTask(String field) throws Exception {
		Field f = PeriodicTasks.class.getDeclaredField(field);
		f.setAccessible(true);
		return (Runnable) f.get(null);
	}
}
