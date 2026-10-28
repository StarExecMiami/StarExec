package org.starexec.test.junit.data.to;

import org.junit.jupiter.api.Test;
import org.starexec.data.to.Status.StatusCode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class StatusTests {

	@Test
	public void runningTest() {
		for (StatusCode status : EnumSet.allOf(StatusCode.class)) {
			if (status == StatusCode.STATUS_RUNNING) {
				assertTrue(status.running());
			} else {
				assertFalse(status.running());
			}
		}
	}

	@Test
	public void statCompleteTest() {
		for (StatusCode status : EnumSet.allOf(StatusCode.class)) {
			if (status == StatusCode.STATUS_COMPLETE
					|| status == StatusCode.EXCEED_RUNTIME
					|| status == StatusCode.EXCEED_CPU
					|| status == StatusCode.EXCEED_FILE_WRITE
					|| status == StatusCode.ERROR_PRE_PROCESSOR
					|| status == StatusCode.ERROR_POST_PROCESSOR
					|| status == StatusCode.EXCEED_MEM) {
				assertTrue(status.statComplete(), "The "+status.toString()+" status should be complete.");
			} else {
				assertFalse(status.statComplete(), "The " + status.toString() + " status should not be complete.");
			}
		}
	}

	@Test
	public void statIncompleteTest() {
		for (StatusCode status : EnumSet.allOf(StatusCode.class)) {
			if (status == StatusCode.STATUS_COMPLETE
					|| status == StatusCode.EXCEED_RUNTIME
					|| status == StatusCode.EXCEED_CPU
					|| status == StatusCode.EXCEED_FILE_WRITE
					|| status == StatusCode.ERROR_PRE_PROCESSOR
					|| status == StatusCode.ERROR_POST_PROCESSOR
					|| status == StatusCode.EXCEED_MEM) {
				assertFalse(status.statIncomplete(), "The "+status.toString()+" status should be complete.");
			} else {
				assertTrue(status.statIncomplete(), "The " + status.toString() + " status should not be complete.");
			}
		}
	}

	@Test
	public void resourceTest() {
		for (StatusCode status : EnumSet.allOf(StatusCode.class)) {
			if (status == StatusCode.EXCEED_RUNTIME
					|| status == StatusCode.EXCEED_CPU
					|| status == StatusCode.EXCEED_FILE_WRITE
					|| status == StatusCode.EXCEED_MEM) {
				assertTrue(status.resource(), "The "+status.toString()+" status should be a \"resource out\" status.");
			} else {
				assertFalse(status.resource(), "The " + status.toString() + " status should not be a \"resource out\" status.");
			}
		}
	}

	@Test
	public void failedTest() {
		for (StatusCode status : EnumSet.allOf(StatusCode.class)) {
			if (status == StatusCode.ERROR_BENCH_DEPENDENCY_MISSING
					|| status == StatusCode.ERROR_GENERAL
					|| status == StatusCode.ERROR_SGE_REJECT
					|| status == StatusCode.ERROR_SUBMIT_FAIL
					|| status == StatusCode.ERROR_RESULTS
					|| status == StatusCode.ERROR_RUNSCRIPT
					|| status == StatusCode.ERROR_BENCHMARK
					|| status == StatusCode.ERROR_PRE_PROCESSOR
					|| status == StatusCode.ERROR_POST_PROCESSOR
					|| status == StatusCode.ERROR_DISK_QUOTA_EXCEEDED) {
				assertTrue(status.failed(), "The "+status.toString()+" status should be a \"failed\" status.");
			} else {
				assertFalse(status.failed(), "The "+status.toString()+" status should not be a \"failed\" status.");
			}
		}
	}

	@Test
	public void incompleteTest() {
		for (StatusCode status : EnumSet.allOf(StatusCode.class)) {
			if (status == StatusCode.STATUS_UNKNOWN
					|| status == StatusCode.STATUS_PENDING_SUBMIT
					|| status == StatusCode.STATUS_ENQUEUED
					|| status == StatusCode.STATUS_RUNNING
					|| status == StatusCode.STATUS_PROCESSING_RESULTS
					|| status == StatusCode.STATUS_PAUSED
					|| status == StatusCode.STATUS_KILLED
					|| status == StatusCode.STATUS_PROCESSING
					|| status == StatusCode.STATUS_NOT_REACHED
					|| status == StatusCode.ERROR_PRE_PROCESSOR
					|| status == StatusCode.ERROR_POST_PROCESSOR
					|| status == StatusCode.ERROR_BENCH_DEPENDENCY_MISSING) {
				assertTrue(status.incomplete(), "The "+status.toString()+" status should be an \"incomplete\" status.");
			} else {
				assertFalse(status.incomplete(), "The "+status.toString()+" status should not be a \"incomplete\" status.");
			}
		}
	}

	/**
	 * Make sure that all status codes used in our shell script exist in our
	 * enum.
	 */
	@Test
	public void enumMatchesShell() {
		final String resourceName = "org/starexec/config/sge/status_codes.bash";

		var inputStream = StatusTests.class.getClassLoader().getResourceAsStream(resourceName);
		if (inputStream == null) {
			fail("Could not find resource: " + resourceName);
		}

		try (
			var reader = new BufferedReader(new InputStreamReader(inputStream))
		) {
			reader.lines() // For every line in the script
				.filter( l -> !l.isEmpty() && l.charAt(0) != '#' ) // Strip all lines that are empty or comments
				.forEach( l -> {
					String[] line = l.split("="); // Split the line at =
					String shellStatusName = line[0].replaceAll("^readonly\\s+",""); // Name is left of =
					int statusCode = Integer.parseInt(line[1]); // Code is right of =
					String enumStatusName = StatusCode.toStatusCode(statusCode).toString(); // Find the corresponding enum status code
					assertEquals(shellStatusName, enumStatusName); // Make sure they match
				})
			;
		} catch (java.io.FileNotFoundException e) {
			fail("File not found: " + resourceName);
		} catch (java.io.IOException e) {
			fail("I/O error reading file: " + resourceName);
		}
	}
}
