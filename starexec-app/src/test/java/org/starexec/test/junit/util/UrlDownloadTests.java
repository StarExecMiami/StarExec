package org.starexec.test.junit.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.constants.R;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code Util.copyFileFromURLUsingProxy} downloads an archive a user names by URL for a solver or
 * processor upload. Only http and https URLs are downloaded, over a direct connection: StarExec's
 * own public address is not an outbound proxy.
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
	public void anHttpUrlIsDownloadedDirectly() throws Exception {
		byte[] served = Files.readAllBytes(archive);
		startServer();
		server.createContext("/solver.zip", exchange -> {
			exchange.sendResponseHeaders(200, served.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(served);
			}
		});

		assertTrue("downloaded directly, not through " + R.PROXY_ADDRESS + ":" + R.PROXY_PORT,
				Util.copyFileFromURLUsingProxy(serverUrl("/solver.zip"), destination));
		assertArrayEquals(served, Files.readAllBytes(destination.toPath()));
	}

	/** A redirect to a scheme other than http(s) is not followed, and its body is not kept. */
	@Test
	public void aRedirectToAnotherSchemeIsNotCopied() throws Exception {
		startServer();
		server.createContext("/moved.zip", exchange -> {
			exchange.getResponseHeaders().add("Location", archive.toUri().toString());
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});

		assertFalse("the redirect is not a download",
				Util.copyFileFromURLUsingProxy(serverUrl("/moved.zip"), destination));
		assertFalse("nothing is written", destination.exists());
	}

	private void startServer() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.start();
	}

	private URL serverUrl(String path) throws Exception {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path).toURL();
	}

	@Test
	public void anFtpUrlIsNotCopied() throws Exception {
		URL url = URI.create("ftp://127.0.0.1:9/solver.zip").toURL();

		assertFalse("an ftp: URL is refused", Util.copyFileFromURLUsingProxy(url, destination));
		assertFalse("nothing is written", destination.exists());
	}
}
