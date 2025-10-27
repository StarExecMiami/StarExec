package org.starexec.test.junit;

import static org.junit.Assert.*;
import org.junit.Test;
import org.starexec.util.Util;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class UtilTests {

	@Test
	public void BytesToGigabytesTest() {
		assertEquals(1, Util.bytesToGigabytes(1073741824),.005);
		assertEquals(0,Util.bytesToGigabytes(0),.005);
	}

	@Test
	public void GetExtensionTest() {
		assertEquals("zip",Util.getFileExtension("this/is/a/fake.zip"));
		assertEquals("test",Util.getFileExtension("fake.test"));
	}

	@Test
	public void GetTempPasswordTest() {
		int index=0;
		while (index<10) {
			index++;
			String pass=Util.getTempPassword();
			assertNotNull(pass);
			assertEquals(pass.length(),Util.clamp(6, 20, pass.length()));
		}
	}

	@Test
	public void ToIntegerListTest() {
		List<Integer> ints=Util.toIntegerList(new String[]{"11","2","321"});
		assertEquals(3,ints.size());
		assertEquals(11,(int)ints.get(0));
		assertEquals(2,(int)ints.get(1));
		assertEquals(321,(int)ints.get(2));

		ints=Util.toIntegerList(new String[]{});
		assertEquals(0,ints.size());
	}

	@Test
	public void intClampTest() {
		assertEquals(1,Util.clamp(0, 10, 1));
		assertEquals(13,Util.clamp(13, 25, 7));
		assertEquals(30,Util.clamp(4, 30, 31));
		assertEquals(10,Util.clamp(10, 10, 10));
	}

	@Test
	public void longClampTest() {
		assertEquals(1,Util.clamp(0L, 10L, 1L));
		assertEquals(13,Util.clamp(13L, 25L, 7L));
		assertEquals(30,Util.clamp(4L, 30L, 31L));
		assertEquals(10,Util.clamp(10L, 10L, 10L));
	}

	@Test
	public void isNullOrEmptyTest() {
		assertTrue(Util.isNullOrEmpty(null));
		assertTrue(Util.isNullOrEmpty(""));
		assertFalse(Util.isNullOrEmpty("a"));
		assertFalse(Util.isNullOrEmpty("another test"));
		assertFalse(Util.isNullOrEmpty("null"));
		assertFalse(Util.isNullOrEmpty("empty"));
	}

	@Test
	public void BytesToMegabytesTest() {
		assertEquals(1, Util.bytesToMegabytes(1048576));
		assertEquals(1, Util.bytesToMegabytes(1048577));
		assertEquals(3, Util.bytesToMegabytes(4048576));
		assertEquals(0, Util.bytesToMegabytes(3));
	}

	@Test
	public void getColorFromStringTest() {
		Color c = Util.getColorFromString("black");
		assertEquals(0, c.getGreen());
		assertEquals(0, c.getBlue());
		assertEquals(0, c.getRed());
	}

	@Test
	public void getNullColorFromStringTest() {
		Color c = Util.getColorFromString("fakecolor");
		assertNull(c);
	}

	@Test
	public void isBinaryFile() throws IOException {
		// Use pom.xml which exists in the project root
		File textFile = new File("pom.xml");
		assertTrue("Text file should exist: " + textFile.getAbsolutePath(), textFile.exists());
		assertFalse("pom.xml should be detected as text file", Util.isBinaryFile(textFile));
		
		// Use a compiled class file from target directory as binary test
		File binaryFile = new File("target/classes/org/starexec/util/Util.class");
		if (binaryFile.exists()) {
			assertTrue("Class file should be detected as binary", Util.isBinaryFile(binaryFile));
		}
		// If class file doesn't exist (e.g., clean build), skip binary test
	}
}
