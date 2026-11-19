package org.starexec.test.junit.util;

import org.junit.jupiter.api.Test;
import org.starexec.util.Util;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Util.childWithin is where a user-chosen name becomes a path component, for solver and processor
 * directories. It must accept exactly one component that names a direct child of the parent, and
 * return the path it checked.
 */
public class ChildWithinTest {

	private static final File PARENT = new File("/solvers/5");

	/**
	 * Each of these resolves to somewhere other than a direct child, or is not one component. "/solvers/5/x"
	 * resolves to the direct child x, but new File(PARENT, "/solvers/5/x") is /solvers/5/solvers/5/x.
	 */
	@Test
	public void aNameThatIsNotOneDirectChildComponentIsRefused() {
		for (String name : List.of("", ".", "..", "../6", "a/..", "a/b", "/etc", "/solvers/5/x", "x/", "./x")) {
			assertThrows(IllegalArgumentException.class, () -> Util.childWithin(PARENT, name),
					"name [" + name + "] must be refused");
		}
	}

	@Test
	public void aDirectChildIsReturnedUnchangedInForm() {
		for (String name : List.of("z3.4", "a..b", "two words", "-x")) {
			assertEquals(new File(PARENT, name), Util.childWithin(PARENT, name));
		}
		// A relative parent stays relative: the result is neither made absolute nor normalized.
		assertEquals(new File("rel/dir", "x"), Util.childWithin(new File("rel/dir"), "x"));
	}
}
