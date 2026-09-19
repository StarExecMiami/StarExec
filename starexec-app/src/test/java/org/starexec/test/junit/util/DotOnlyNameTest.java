package org.starexec.test.junit.util;

import org.junit.BeforeClass;
import org.junit.Test;
import org.starexec.data.database.Solvers;
import org.starexec.servlets.ProcessorManager;
import org.starexec.util.Validator;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Primitive names become directory names on disk, where "." is the directory itself and ".."
 * its parent. A processor named ".." was stored one level above its own directory, and deleting
 * it removed its whole community's processors; a solver named ".." landed outside its user's
 * directory.
 */
public class DotOnlyNameTest {

	private static final List<String> DOT_ONLY = List.of(".", "..", "...", "....");

	/** Names with dots in them that are ordinary names, accepted before and after. */
	private static final List<String> ORDINARY = List.of("z3.4", "a..b", ".hidden", "v1.", "solver");

	private static final List<Predicate<String>> PRIMITIVE_VALIDATORS = List.of(
			Validator::isValidSolverName,
			Validator::isValidProcessorName,
			Validator::isValidBenchName,
			Validator::isValidConfigurationName,
			Validator::isValidJobName,
			Validator::isValidSettingsName,
			Validator::isValidPipelineName,
			Validator::isValidQueueName,
			Validator::isValidWebsiteName,
			Validator::isValidSpaceName);

	@BeforeClass
	public static void initialize() {
		Validator.initialize();
	}

	@Test
	public void everyPrimitiveRejectsNamesMadeOnlyOfDots() {
		for (int i = 0; i < PRIMITIVE_VALIDATORS.size(); i++) {
			for (String name : DOT_ONLY) {
				assertFalse("validator " + i + " must reject [" + name + "]",
						PRIMITIVE_VALIDATORS.get(i).test(name));
			}
		}
	}

	@Test
	public void everyPrimitiveStillAcceptsOrdinaryNamesContainingDots() {
		for (int i = 0; i < PRIMITIVE_VALIDATORS.size(); i++) {
			for (String name : ORDINARY) {
				assertTrue("validator " + i + " must accept [" + name + "]",
						PRIMITIVE_VALIDATORS.get(i).test(name));
			}
		}
	}

	@Test
	public void aProcessorDirectoryCannotLeaveItsDateDirectory() {
		File root = new File("/processors");
		for (String name : List.of(".", "..")) {
			assertThrows("processor [" + name + "] must be refused", IllegalArgumentException.class,
					() -> ProcessorManager.processorDirectory(root, 7, "20260919", name));
		}
		assertEquals(new File("/processors/7/20260919/a..b"),
				ProcessorManager.processorDirectory(root, 7, "20260919", "a..b"));
	}

	@Test
	public void aSolverDirectoryCannotLeaveItsUsersDirectory() {
		File root = new File("/solvers");
		for (String name : List.of(".", "..")) {
			assertThrows("solver [" + name + "] must be refused", IllegalArgumentException.class,
					() -> Solvers.solverDirectory(root, 5, name, "20260919"));
		}
		File ordinary = Solvers.solverDirectory(root, 5, "z3.4", "20260919");
		assertEquals(new File("/solvers/5/z3.4/20260919"), ordinary);
		assertTrue(ordinary.toPath().normalize().startsWith(Path.of("/solvers/5")));
	}
}
