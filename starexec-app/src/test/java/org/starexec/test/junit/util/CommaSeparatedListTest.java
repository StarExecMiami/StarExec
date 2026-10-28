package org.starexec.test.junit.util;

import org.junit.Test;
import org.starexec.util.Util;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The empty list has a representation: the empty string.
 *
 * <p>This used to append a comma per element and delete the last character, so an empty list
 * deleted a character that was never written and threw StringIndexOutOfBoundsException. Every
 * caller puts the result in a New_ID cookie, and at least one reached it with nothing selected,
 * where the throw became an HTTP 500 instead of a JSON status.
 */
public class CommaSeparatedListTest {

	@Test
	public void anEmptyListIsTheEmptyString() {
		assertEquals("", Util.makeCommaSeparatedList(List.of()));
	}

	@Test
	public void aSingleIdHasNoComma() {
		assertEquals("7", Util.makeCommaSeparatedList(List.of(7)));
	}

	@Test
	public void severalIdsAreCommaSeparatedWithNoTrailingComma() {
		assertEquals("7,8,9", Util.makeCommaSeparatedList(List.of(7, 8, 9)));
	}
}
