package org.starexec.test.unit.jobs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where bash is, as an absolute path.
 *
 * <p>The tests in this package execute the shipped job-script helper, which means starting an
 * interpreter. Naming it {@code "bash"} leaves the choice to {@code PATH}, so what actually
 * runs depends on the environment the build happens to be in -- flagged by CodeQL as
 * {@code java/relative-path-command}. It matters less in a test than in the application, but
 * the answer is the same either way and costs nothing here.
 *
 * <p>Shared rather than duplicated because both classes in this package start a shell, and
 * only one of the two call shapes was detectable: passing {@code "bash"} through a varargs
 * array hid it from the scanner. One resolver keeps them from drifting apart again.
 */
final class Bash {

	/** Absolute, in preference order. Both exist on Debian/Ubuntu, where CI runs. */
	private static final String[] CANDIDATES = {"/bin/bash", "/usr/bin/bash"};

	/** The interpreter these tests run scripts with. */
	static final String PATH = resolve();

	private Bash() {
	}

	private static String resolve() {
		for (String candidate : CANDIDATES) {
			if (Files.isExecutable(Path.of(candidate))) {
				return candidate;
			}
		}
		throw new IllegalStateException("no bash found at " + String.join(" or ", CANDIDATES)
				+ "; the job-script helper tests in this package cannot run without one");
	}
}
