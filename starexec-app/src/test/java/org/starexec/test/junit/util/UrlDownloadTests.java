package org.starexec.test.junit.util;

import org.junit.Before;
import org.junit.Test;
import org.starexec.util.Util;

import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;

/**
 * {@code Util.copyFileFromURLUsingProxy} downloads an archive a user names by URL for a solver or
 * processor upload. Only http and https URLs are downloaded.
 */
public class UrlDownloadTests {

	private static final byte[] ARCHIVE_BYTES = "archive on the server".getBytes(StandardCharsets.UTF_8);

	private Path dir;
	private Path archive;
	private File destination;

	@Before
	public void createArchive() throws Exception {
		dir = Files.createTempDirectory("url-download-");
		archive = dir.resolve("solver.zip");
		try (OutputStream out = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("entry.txt"));
			zip.write(ARCHIVE_BYTES);
			zip.closeEntry();
		}
		destination = dir.resolve("downloaded.zip").toFile();
	}

	@Test
	public void aFileUrlIsNotCopied() throws Exception {
		URL url = archive.toUri().toURL();

		assertFalse("a file: URL is refused", Util.copyFileFromURLUsingProxy(url, destination));
		assertFalse("nothing is written", destination.exists());
	}

	@Test
	public void aJarUrlIsNotCopied() throws Exception {
		URL url = URI.create("jar:" + archive.toUri() + "!/entry.txt").toURL();

		assertFalse("a jar: URL is refused", Util.copyFileFromURLUsingProxy(url, destination));
		assertFalse("nothing is written", destination.exists());
	}

	@Test
	public void anFtpUrlIsNotCopied() throws Exception {
		URL url = URI.create("ftp://127.0.0.1:9/solver.zip").toURL();

		assertFalse("an ftp: URL is refused", Util.copyFileFromURLUsingProxy(url, destination));
		assertFalse("nothing is written", destination.exists());
	}
}
