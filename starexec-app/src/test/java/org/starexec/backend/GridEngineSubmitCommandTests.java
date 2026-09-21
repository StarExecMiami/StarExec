package org.starexec.backend;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The qsub call is argv, not a command line.
 *
 * <p>{@code submitScript} used to build its command with a {@link StringBuilder} and wrap
 * the result in a one-element array. {@code Util.buildProcess} turned a one-element array
 * into {@code /bin/sh -c <element>}, so the whole thing was parsed by a shell. Three of
 * the values interpolated into it are paths supplied by the caller, and none of them were
 * quoted or validated.
 *
 * <p>Two consequences, both fixed by passing argv: a metacharacter in a path was
 * interpreted rather than passed along, and an ordinary space in a path silently became
 * an argument boundary.
 *
 * <p>This test lives in package {@code org.starexec.backend} because {@code submitCommand}
 * is package-private -- it is an internal seam, not new public surface.
 */
public class GridEngineSubmitCommandTests {

	@Test
	public void theCommandIsBuiltAsSeparateArguments() {
		String[] argv = GridEngineBackend.submitCommand("/s/job.bash", "/w", "/l/out.log");

		assertEquals(
				Arrays.asList("qsub", "-b", "n", "-v", "TMPDIR=/w", "-o", "/l/out.log",
						"-terse", "/s/job.bash"),
				Arrays.asList(argv));
	}

	/**
	 * A path containing a space stays one argument.
	 *
	 * <p>The old form produced {@code ... -o /var/log dir/out.log -terse ...}, which a
	 * shell splits into two words: qsub received {@code /var/log} as its log path and
	 * {@code dir/out.log} as a positional argument.
	 */
	@Test
	public void aPathContainingASpaceIsNotSplit() {
		String[] argv = GridEngineBackend.submitCommand(
				"/s/my script.bash", "/w space", "/var/log dir/out.log");

		assertTrue("the script path must survive as one argument",
				Arrays.asList(argv).contains("/s/my script.bash"));
		assertTrue("the log path must survive as one argument",
				Arrays.asList(argv).contains("/var/log dir/out.log"));
		assertTrue("TMPDIR must carry the whole path",
				Arrays.asList(argv).contains("TMPDIR=/w space"));
	}

	/**
	 * A shell metacharacter in a path is data, not syntax.
	 *
	 * <p>Under the old form {@code /tmp/x;touch owned} ended the qsub command and started
	 * a second one. Here it must remain a single, inert element.
	 */
	@Test
	public void shellMetacharactersInAPathStayInOneArgument() {
		String[] argv = GridEngineBackend.submitCommand(
				"/s/job.bash", "/tmp/x;touch owned", "/l/out.log");

		assertTrue("the metacharacter must stay inside one argument",
				Arrays.asList(argv).contains("TMPDIR=/tmp/x;touch owned"));
		for (String element : argv) {
			assertTrue("no element may be a bare injected command: " + element,
					!element.equals("touch") && !element.equals("owned"));
		}
	}

	/** Every element is non-null, so nothing reaches ProcessBuilder as a null argument. */
	@Test
	public void noArgumentIsNull() {
		String[] argv = GridEngineBackend.submitCommand("/s/job.bash", "/w", "/l/out.log");
		assertEquals("the qsub argv has a fixed shape", 9, argv.length);
		for (String element : argv) {
			assertTrue("null argument in argv", element != null);
		}
	}
}
