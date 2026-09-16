package org.starexec.test.junit.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.util.Util;

import java.io.File;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * {@code Util.copyFileFromURLUsingProxy} downloads an archive a user names by URL for a solver or
 * processor upload. Only http and https URLs to public destinations are downloaded; the policy
 * itself is exercised against loopback servers in {@code org.starexec.util.ArchiveUrlDownloaderTests}.
 */
public class UrlDownloadTests {

	private static final byte[] ARCHIVE_BYTES = "archive on the server".getBytes(StandardCharsets.UTF_8);

	private Path dir;
	private Path archive;
	private File destination;
	private HttpServer server;

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

	@After
	public void stopServer() {
		if (server != null) {
			server.stop(0);
		}
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

	/** Local destinations are refused whether they are named or written as an address. */
	@Test
	public void aServerOnLoopbackIsNotContacted() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		byte[] served = Files.readAllBytes(archive);
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/solver.zip", exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, served.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(served);
			}
		});
		server.start();
		int port = server.getAddress().getPort();

		for (String host : new String[] {"localhost", "127.0.0.1", "[::ffff:127.0.0.1]"}) {
			URL url = URI.create("http://" + host + ":" + port + "/solver.zip").toURL();
			assertFalse(host + " is refused", Util.copyFileFromURLUsingProxy(url, destination));
		}
		assertEquals("the server is never contacted", 0, requests.get());
		assertFalse("nothing is written", destination.exists());
	}
}
