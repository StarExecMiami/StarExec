package org.starexec.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.starexec.test.TemporaryFolders.newFolder;

/**
 * {@link ArchiveUtil#extractArchive(String)} says whether it extracted the archive, so that an upload can tell an
 * archive it could not open from one that holds nothing. The archives are real files.
 */
public class ArchiveUtilExtractArchiveTest {
	@TempDir
	public File tmp;

	@Test
	public void aZipArchiveIsExtractedBesideItself() throws IOException {
		File dir = newFolder(tmp, "upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), zipHolding("space.xml", "<space/>\n"));

		assertTrue(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
		assertEquals("<space/>\n", Files.readString(new File(dir, "space.xml").toPath()));
	}

	/** Bytes that start like a zip archive but are not one. */
	@Test
	public void bytesThatAreNotAZipArchiveAreNotExtracted() throws IOException {
		File dir = newFolder(tmp, "upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), "PK\u0003\u0004 these bytes are not a zip archive".getBytes(StandardCharsets.ISO_8859_1));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
	}

	@Test
	public void aTruncatedZipArchiveIsNotExtracted() throws IOException {
		File dir = newFolder(tmp, "upload");
		File zip = new File(dir, "spaces.zip");
		byte[] whole = zipHolding("space.xml", "<space/>\n".repeat(1000));
		Files.write(zip.toPath(), Arrays.copyOf(whole, whole.length / 2));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
	}

	/** Refused for safety. */
	@Test
	public void aZipArchiveWithDotDotInAFileNameIsNotExtracted() throws IOException {
		File dir = newFolder(tmp, "upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), zipHolding("../space.xml", "<space/>\n"));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
		assertFalse(new File(tmp, "space.xml").exists(), "nothing is written beside the upload's directory");
	}

	/** Refused for safety. */
	@Test
	public void aZipArchiveThatWouldExpandFarBeyondItsSizeIsNotExtracted() throws IOException {
		File dir = newFolder(tmp, "upload");
		File zip = new File(dir, "spaces.zip");
		Files.write(zip.toPath(), declaringItsEntryExpandsTo(Integer.MAX_VALUE, zipHolding("space.xml", "<space/>\n")));

		assertFalse(ArchiveUtil.extractArchive(zip.getAbsolutePath()));
		assertFalse(new File(dir, "space.xml").exists(), "nothing is extracted");
	}

	@Test
	public void anArchiveThatIsNotThereIsNotExtracted() {
		assertFalse(ArchiveUtil.extractArchive(new File(tmp, "missing.zip").getAbsolutePath()));
	}

	/**
	 * A file name with no directory leaves nowhere to extract to. The method returns false for that too; it does
	 * not throw.
	 */
	@Test
	public void aPathWithNoDirectoryIsNotExtracted() {
		assertFalse(ArchiveUtil.extractArchive("spaces.zip"));
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
		assertEquals(1, headers, "central directory headers");
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

	@Test
	public void tarGzAndZipSymlinksAreRejectedInBothExtractors() throws IOException {
		File tarDir = newFolder(tmp, "tar-sym");
		File tgz = new File(tarDir, "bad.tar.gz");
		Files.write(tgz.toPath(), tarGzWithEntry("link", "/etc/passwd", TarConstants.LF_SYMLINK, null));

		File dest1 = newFolder(tmp, "dest-extract");
		File dest2 = newFolder(tmp, "dest-sandbox");
		assertFalse(ArchiveUtil.extractArchive(tgz.getAbsolutePath(), dest1.getAbsolutePath()));
		assertFalse(ArchiveUtil.extractArchiveAsSandbox(tgz.getAbsolutePath(), dest2.getAbsolutePath()));
		assertFalse(Files.exists(dest1.toPath().resolve("link")));
		assertFalse(Files.exists(dest2.toPath().resolve("link")));

		File hardTgz = new File(tarDir, "hard.tgz");
		Files.write(hardTgz.toPath(), tarGzWithEntry("hard", "/etc/passwd", TarConstants.LF_LINK, null));
		assertFalse(ArchiveUtil.extractArchive(hardTgz.getAbsolutePath(), dest1.getAbsolutePath()));
		assertFalse(ArchiveUtil.extractArchiveAsSandbox(hardTgz.getAbsolutePath(), dest2.getAbsolutePath()));

		File symZip = new File(tarDir, "sym.zip");
		ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
		try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(zipBytes)) {
			ZipArchiveEntry entry = new ZipArchiveEntry("link");
			entry.setUnixMode(UnixStat.LINK_FLAG | 0777);
			zos.putArchiveEntry(entry);
			zos.write("/etc/passwd".getBytes(StandardCharsets.UTF_8));
			zos.closeArchiveEntry();
		}
		Files.write(symZip.toPath(), zipBytes.toByteArray());
		assertFalse(ArchiveUtil.extractArchive(symZip.getAbsolutePath(), dest1.getAbsolutePath()));
		assertFalse(ArchiveUtil.extractArchiveAsSandbox(symZip.getAbsolutePath(), dest2.getAbsolutePath()));
	}

	@Test
	public void validTarGzExtractsAndHighRatioTarGzBombIsRejected() throws IOException {
		File dir = newFolder(tmp, "tar-ok");
		File validTgz = new File(dir, "solver.tar.gz");
		Files.write(validTgz.toPath(), tarGzWithEntry("bin/run.sh", null, TarConstants.LF_NORMAL, "#!/bin/sh\n"));
		File dest = newFolder(tmp, "dest-ok");

		assertTrue(ArchiveUtil.extractArchive(validTgz.getAbsolutePath(), dest.getAbsolutePath()));
		assertEquals("#!/bin/sh\n", Files.readString(dest.toPath().resolve("bin/run.sh")));

		File bombDir = newFolder(tmp, "tar-bomb");
		File bombTgz = new File(bombDir, "bomb.tar.gz");
		byte[] sixteenMegsOfZeros = new byte[16 * 1024 * 1024];
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(baos);
			 TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
			TarArchiveEntry entry = new TarArchiveEntry("zeros.bin");
			entry.setSize(sixteenMegsOfZeros.length);
			tos.putArchiveEntry(entry);
			tos.write(sixteenMegsOfZeros);
			tos.closeArchiveEntry();
		}
		Files.write(bombTgz.toPath(), baos.toByteArray());
		File bombDest = newFolder(tmp, "dest-bomb");
		assertFalse(ArchiveUtil.extractArchive(bombTgz.getAbsolutePath(), bombDest.getAbsolutePath()));
		assertFalse(Files.exists(bombDest.toPath().resolve("zeros.bin")));
	}

	@Test
	public void tarTgzTarGzAndZipPreserveExecutableBitsAndStripSpecialModeBits() throws IOException {
		File tarFile = new File(newFolder(tmp, "exec-tar"), "solver.tar");
		try (TarArchiveOutputStream tos = new TarArchiveOutputStream(Files.newOutputStream(tarFile.toPath()))) {
			addTarEntryWithMode(tos, "bin/starexec_run_default", "#!/bin/sh\n", 0104755);
			addTarEntryWithMode(tos, "bin/readme.txt", "doc\n", 0100644);
			tos.finish();
		}
		File tarDest = newFolder(tmp, "dest-tar");
		assertTrue(ArchiveUtil.extractArchive(tarFile.getAbsolutePath(), tarDest.getAbsolutePath()));
		Path tarBin = tarDest.toPath().resolve("bin/starexec_run_default");
		Path tarDoc = tarDest.toPath().resolve("bin/readme.txt");
		assertTrue(Files.isExecutable(tarBin));
		assertFalse(Files.isExecutable(tarDoc));
		assertEquals(0755, ((Integer) Files.getAttribute(tarBin, "unix:mode")) & 07777);

		for (String ext : new String[]{".tgz", ".tar.gz"}) {
			File tgzFile = new File(newFolder(tmp, "exec-" + ext.replace(".", "")), "solver" + ext);
			try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(Files.newOutputStream(tgzFile.toPath()));
				 TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
				addTarEntryWithMode(tos, "bin/starexec_run_default", "#!/bin/sh\n", 0100755);
				tos.finish();
			}
			File tgzDest = newFolder(tmp, "dest-" + ext.replace(".", ""));
			assertTrue(ArchiveUtil.extractArchive(tgzFile.getAbsolutePath(), tgzDest.getAbsolutePath()));
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(
					tgzDest.toPath().resolve("bin/starexec_run_default"));
			assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE), "Missing +x for " + ext);
		}

		File zipFile = new File(newFolder(tmp, "exec-zip"), "solver.zip");
		try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(Files.newOutputStream(zipFile.toPath()))) {
			ZipArchiveEntry exec = new ZipArchiveEntry("bin/solver");
			exec.setUnixMode(UnixStat.FILE_FLAG | 04755);
			byte[] execBytes = "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8);
			zos.putArchiveEntry(exec);
			zos.write(execBytes);
			zos.closeArchiveEntry();

			ZipArchiveEntry doc = new ZipArchiveEntry("bin/notes.txt");
			doc.setUnixMode(UnixStat.FILE_FLAG | 0644);
			zos.putArchiveEntry(doc);
			zos.write("notes\n".getBytes(StandardCharsets.UTF_8));
			zos.closeArchiveEntry();
		}
		File zipDest = newFolder(tmp, "dest-zip");
		assertTrue(ArchiveUtil.extractArchive(zipFile.getAbsolutePath(), zipDest.getAbsolutePath()));
		Path zipBin = zipDest.toPath().resolve("bin/solver");
		Path zipDoc = zipDest.toPath().resolve("bin/notes.txt");
		assertTrue(Files.isExecutable(zipBin));
		assertFalse(Files.isExecutable(zipDoc));
		assertEquals(0755, ((Integer) Files.getAttribute(zipBin, "unix:mode")) & 07777);
	}

	private static void addTarEntryWithMode(TarArchiveOutputStream tos, String name, String content, int mode)
			throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(bytes.length);
		entry.setMode(mode);
		tos.putArchiveEntry(entry);
		tos.write(bytes);
		tos.closeArchiveEntry();
	}

	private static byte[] tarGzWithEntry(String name, String linkName, byte linkFlag, String content) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(baos);
			 TarArchiveOutputStream tos = new TarArchiveOutputStream(gz)) {
			TarArchiveEntry entry = new TarArchiveEntry(name, linkFlag);
			if (linkName != null) {
				entry.setLinkName(linkName);
			}
			if (content != null) {
				byte[] data = content.getBytes(StandardCharsets.UTF_8);
				entry.setSize(data.length);
				tos.putArchiveEntry(entry);
				tos.write(data);
			} else {
				tos.putArchiveEntry(entry);
			}
			tos.closeArchiveEntry();
		}
		return baos.toByteArray();
	}
}
