package org.starexec.test.junit.util;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.constants.R;
import org.starexec.util.PictureFiles;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The picture layout that upload writes and delete removes. It has to be the one GetPicture
 * reads -- {@code <dir>/Pic<id>_org.jpg} and {@code _thn.jpg} under users, solvers and
 * benchmarks -- or a deleted picture would still be served, or an upload never shown.
 */
public class PictureFilesTest {

	@Test
	public void namesTheFilesGetPictureReads() {
		try (MockedStatic<R> r = Mockito.mockStatic(R.class, Mockito.CALLS_REAL_METHODS)) {
			r.when(R::getPicturePath).thenReturn("/pics");

			assertEquals(new File("/pics/users/Pic7_org.jpg"), PictureFiles.original("user", 7));
			assertEquals(new File("/pics/users/Pic7_thn.jpg"), PictureFiles.thumbnail("user", 7));
			assertEquals(new File("/pics/solvers/Pic3_org.jpg"), PictureFiles.original("solver", 3));
			assertEquals(new File("/pics/benchmarks/Pic4_thn.jpg"), PictureFiles.thumbnail("benchmark", 4));
		}
	}

	@Test
	public void knowsExactlyTheThreeTypes() {
		assertTrue(PictureFiles.isKnownType("user"));
		assertTrue(PictureFiles.isKnownType("solver"));
		assertTrue(PictureFiles.isKnownType("benchmark"));
		assertFalse(PictureFiles.isKnownType("space"));
		assertFalse(PictureFiles.isKnownType(null));
	}

	@Test(expected = IllegalArgumentException.class)
	public void refusesToNameAFileForAnUnknownType() {
		PictureFiles.original("space", 1);
	}
}
