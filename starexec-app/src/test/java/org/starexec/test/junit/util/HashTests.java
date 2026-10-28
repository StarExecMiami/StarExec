package org.starexec.test.junit.util;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.starexec.util.Hash;

public class HashTests {

	@Test
	public void testGetHex() {
		Assertions.assertEquals("", Hash.getHex(new byte[]{}));
		Assertions.assertEquals("0a", Hash.getHex(new byte[]{10}));
		Assertions.assertEquals("7f0010", Hash.getHex(new byte[]{127, 0, 16}));
	}
}
