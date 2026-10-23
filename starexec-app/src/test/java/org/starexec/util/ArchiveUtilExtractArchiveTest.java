package org.starexec.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ArchiveUtil#extractArchive(String)} says whether it extracted the archive, so that an upload can tell an
 * archive it could not open from one that holds nothing. The archives are real files.
 */
public class ArchiveUtilExtractArchiveTest {
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void aZipArchiveIsExtractedBesideItself() throws IOException {
		File dir = tmp.newFolder("upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), zipHolding("space.xml", "<space/>\n"));

		assertTrue(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
		assertEquals("<space/>\n", Files.readString(new File(dir, "space.xml").toPath()));
	}

	/** Bytes that start like a zip archive but are not one. */
	@Test
	public void bytesThatAreNotAZipArchiveAreNotExtracted() throws IOException {
		File dir = tmp.newFolder("upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), "PK\u0003\u0004 these bytes are not a zip archive".getBytes(StandardCharsets.ISO_8859_1));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
	}

	@Test
	public void aTruncatedZipArchiveIsNotExtracted() throws IOException {
		File dir = tmp.newFolder("upload");
		File zip = new File(dir, "spaces.zip");
		byte[] whole = zipHolding("space.xml", "<space/>\n".repeat(1000));
		Files.write(zip.toPath(), Arrays.copyOf(whole, whole.length / 2));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
	}

	/** Refused for safety. */
	@Test
	public void aZipArchiveWithDotDotInAFileNameIsNotExtracted() throws IOException {
		File dir = tmp.newFolder("upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), zipHolding("../space.xml", "<space/>\n"));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
		assertFalse("nothing is written beside the upload's directory", new File(tmp.getRoot(), "space.xml").exists());
	}

	/** Refused for safety. */
	@Test
	public void aZipArchiveThatWouldExpandFarBeyondItsSizeIsNotExtracted() throws IOException {
		File dir = tmp.newFolder("upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), declaringItsEntryExpandsTo(Integer.MAX_VALUE, zipHolding("space.xml", "<space/>\n")));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
		assertFalse("nothing is extracted", new File(dir, "space.xml").exists());
	}

	@Test
	public void anArchiveThatIsNotThereIsNotExtracted() {
		assertFalse(ArchiveUtil.extractArchive(new File(tmp.getRoot(), "missing.zip").getAbsolutePath()));
	}

	/**
	 * Returns the given one-entry zip archive with the uncompressed size in its central directory set to
	 * {@code size}.
	 */
	private static byte[] declaringItsEntryExpandsTo(int size, byte[] zip) {
		ByteBuffer bytes = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
		int headers = 0;
		for (int i = 0; i + 28 <= zip.length; i++) {
			if (bytes.getInt(i) == 0x02014b50) { // a central directory file header
				bytes.putInt(i + 24, size); // its uncompressed size
				headers++;
			}
		}
		assertEquals("central directory headers", 1, headers);
		return zip;
	}

	private static byte[] zipHolding(String name, String content) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(bytes)) {
			out.putNextEntry(new ZipEntry(name));
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return bytes.toByteArray();
	}
}
