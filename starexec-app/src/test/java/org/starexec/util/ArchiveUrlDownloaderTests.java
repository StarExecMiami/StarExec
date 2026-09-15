package org.starexec.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.starexec.util.ArchiveUrlDownloader.Policy;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Archive URL downloads connect only to public destinations, follow a bounded number of
 * redirects, re-check every hop, and stop at a size cap and an overall deadline without leaving
 * a partial file.
 *
 * <p>The servers here listen on loopback, which the standard policy refuses, so each test names
 * the one loopback address it permits.
 */
public class ArchiveUrlDownloaderTests {

	private static final byte[] ARCHIVE = "archive bytes".getBytes(StandardCharsets.UTF_8);

	private final List<HttpServer> servers = new ArrayList<>();
	private Path dir;
	private File destination;

	@Before
	public void createDestination() throws Exception {
		dir = Files.createTempDirectory("archive-download-");
		destination = dir.resolve("downloaded.zip").toFile();
	}

	@After
	public void stopServers() {
		for (HttpServer server : servers) {
			server.stop(0);
		}
	}

	@Test
	public void publicAddressesArePermitted() throws Exception {
		for (String address : List.of("93.184.216.34", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888")) {
			assertTrue(address, ArchiveUrlDownloader.isPermittedAddress(InetAddress.getByName(address), standard()));
		}
	}

	@Test
	public void localAndPrivateAddressesAreRefused() throws Exception {
		List<String> permitted = new ArrayList<>();
		for (String address : List.of(PRIVATE_ADDRESSES, NEVER_PERMITTED_ADDRESSES).stream()
				.flatMap(List::stream).toList()) {
			if (ArchiveUrlDownloader.isPermittedAddress(InetAddress.getByName(address), standard())) {
				permitted.add(address);
			}
		}
		assertEquals(List.of(), permitted);
	}

	/** A name that looks public is judged by the address it resolves to. */
	@Test
	public void aPublicLookingNameResolvingToLoopbackIsRefused() throws Exception {
		InetAddress address = InetAddress.getByAddress("archives.example.org", new byte[] {127, 0, 0, 1});

		assertFalse(ArchiveUrlDownloader.isPermittedAddress(address, standard()));
	}

	@Test
	public void thePrivateNetworkSettingLiftsOnlyPrivateRanges() throws Exception {
		Policy onPremises = new Policy(true, Set.of(), 5, Long.MAX_VALUE, 60_000, 5_000, 5_000);
		List<String> wrong = new ArrayList<>();
		for (String address : PRIVATE_ADDRESSES) {
			if (!ArchiveUrlDownloader.isPermittedAddress(InetAddress.getByName(address), onPremises)) {
				wrong.add("refused " + address);
			}
		}
		for (String address : NEVER_PERMITTED_ADDRESSES) {
			if (ArchiveUrlDownloader.isPermittedAddress(InetAddress.getByName(address), onPremises)) {
				wrong.add("permitted " + address);
			}
		}
		assertEquals(List.of(), wrong);
	}

	@Test
	public void redirectsMayKeepTheirSchemeOrUpgradeToHttps() throws Exception {
		assertTrue(ArchiveUrlDownloader.isPermittedRedirect(url("http://a.org/x"), url("http://b.org/x")));
		assertTrue(ArchiveUrlDownloader.isPermittedRedirect(url("https://a.org/x"), url("https://b.org/x")));
		assertTrue(ArchiveUrlDownloader.isPermittedRedirect(url("http://a.org/x"), url("https://b.org/x")));
	}

	@Test
	public void redirectsMayNotDowngradeOrChangeScheme() throws Exception {
		assertFalse(ArchiveUrlDownloader.isPermittedRedirect(url("https://a.org/x"), url("http://b.org/x")));
		assertFalse(ArchiveUrlDownloader.isPermittedRedirect(url("http://a.org/x"), url("ftp://b.org/x")));
		assertFalse(ArchiveUrlDownloader.isPermittedRedirect(url("https://a.org/x"), url("file:///x")));
	}

	@Test
	public void anArchiveFromAPermittedAddressIsDownloaded() throws Exception {
		HttpServer server = serveArchive(LOOPBACK, "/solver.zip");

		assertTrue(ArchiveUrlDownloader.download(urlOf(server, "/solver.zip"), destination, permitting(LOOPBACK)));
		assertArrayEquals(ARCHIVE, Files.readAllBytes(destination.toPath()));
	}

	/** A redirect to a scheme other than http(s) is not followed, and its body is not kept. */
	@Test
	public void aRedirectToAnotherSchemeIsNotFollowed() throws Exception {
		Path local = Files.write(dir.resolve("local.zip"), ARCHIVE);
		HttpServer server = redirect(LOOPBACK, "/moved.zip", local.toUri().toString());

		assertFalse(ArchiveUrlDownloader.download(urlOf(server, "/moved.zip"), destination, permitting(LOOPBACK)));
		assertFalse("nothing is written", destination.exists());
	}

	/** The destination policy applies to every hop, not only to the URL the user named. */
	@Test
	public void aRedirectToARefusedAddressIsNotFollowed() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		HttpServer refused = serveArchive(SECOND_LOOPBACK, "/internal.zip", requests);
		HttpServer server = redirect(LOOPBACK, "/moved.zip", urlOf(refused, "/internal.zip").toString());

		assertFalse(ArchiveUrlDownloader.download(urlOf(server, "/moved.zip"), destination, permitting(LOOPBACK)));
		assertEquals("the refused address is never contacted", 0, requests.get());
		assertFalse("nothing is written", destination.exists());
	}

	@Test
	public void aRelativeRedirectIsResolvedAgainstTheCurrentUrl() throws Exception {
		HttpServer server = serveArchive(LOOPBACK, "/files/solver.zip");
		server.createContext("/files/latest", exchange -> {
			exchange.getResponseHeaders().add("Location", "solver.zip");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});

		assertTrue(ArchiveUrlDownloader.download(urlOf(server, "/files/latest"), destination, permitting(LOOPBACK)));
		assertArrayEquals(ARCHIVE, Files.readAllBytes(destination.toPath()));
	}

	@Test
	public void theRedirectLimitIsEnforced() throws Exception {
		HttpServer server = serveArchive(LOOPBACK, "/hop0");
		for (int hop = 1; hop <= 6; hop++) {
			String target = "/hop" + (hop - 1);
			server.createContext("/hop" + hop, exchange -> {
				exchange.getResponseHeaders().add("Location", target);
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
			});
		}

		assertTrue("five redirects are followed",
				ArchiveUrlDownloader.download(urlOf(server, "/hop5"), destination, permitting(LOOPBACK)));
		assertTrue(destination.delete());
		assertFalse("a sixth is not",
				ArchiveUrlDownloader.download(urlOf(server, "/hop6"), destination, permitting(LOOPBACK)));
		assertFalse("nothing is written", destination.exists());
	}

	/** The size cap matches the benchmark upload form's; the deadline bounds a slow server. */
	@Test
	public void theStandardPolicyBoundsSizeAndDuration() {
		Policy standard = Policy.standard();

		assertEquals(5L * 1024 * 1024 * 1024, standard.maxBytes);
		assertEquals(30L * 60 * 1000, standard.deadlineMillis);
		assertEquals(60_000, standard.readTimeoutMillis);
	}

	@Test
	public void aDeclaredLengthOverTheCapIsRefused() throws Exception {
		HttpServer server = start(LOOPBACK);
		server.createContext("/large.zip", exchange -> {
			exchange.sendResponseHeaders(200, 1000);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(new byte[1000]);
			}
		});

		assertFalse(ArchiveUrlDownloader.download(urlOf(server, "/large.zip"), destination, limited(100, 60_000)));
		assertNothingLeft();
	}

	@Test
	public void anUndeclaredBodyOverTheCapIsAbandoned() throws Exception {
		HttpServer server = start(LOOPBACK);
		server.createContext("/large.zip", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				for (int i = 0; i < 100; i++) {
					body.write(new byte[100]);
				}
			}
		});

		assertFalse(ArchiveUrlDownloader.download(urlOf(server, "/large.zip"), destination, limited(1000, 60_000)));
		assertNothingLeft();
	}

	@Test
	public void aBodyOfExactlyTheCapIsDownloaded() throws Exception {
		HttpServer server = start(LOOPBACK);
		server.createContext("/solver.zip", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(ARCHIVE);
			}
		});

		assertTrue(ArchiveUrlDownloader.download(urlOf(server, "/solver.zip"), destination,
				limited(ARCHIVE.length, 60_000)));
		assertArrayEquals(ARCHIVE, Files.readAllBytes(destination.toPath()));
	}

	/**
	 * A server that trickles its response headers, each byte within the read timeout, is cut off at
	 * the deadline too, not only one that trickles its body.
	 */
	@Test
	public void slowResponseHeadersAreAbandonedAtTheDeadline() throws Exception {
		try (ServerSocket listener = new ServerSocket(0, 1, LOOPBACK)) {
			Thread server = new Thread(() -> trickleHeaders(listener), "slow-headers");
			server.setDaemon(true);
			server.start();
			URL url = url("http://" + LOOPBACK.getHostAddress() + ":" + listener.getLocalPort() + "/slow.zip");

			long started = System.nanoTime();
			assertFalse(ArchiveUrlDownloader.download(url, destination, limited(Long.MAX_VALUE, 500)));
			long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
			assertTrue("abandoned near the deadline, after " + elapsedMillis + " ms", elapsedMillis < 3000);
			assertNothingLeft();
		}
	}

	/** Sends a status line, then one header byte every 50 ms for ten seconds, never ending the headers. */
	private static void trickleHeaders(ServerSocket listener) {
		try (Socket socket = listener.accept(); OutputStream out = socket.getOutputStream()) {
			out.write("HTTP/1.1 200 OK\r\nX-Slow: ".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			for (int i = 0; i < 200; i++) {
				out.write('x');
				out.flush();
				Thread.sleep(50);
			}
		} catch (IOException e) {
			// The client hung up.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Each byte arrives well within the read timeout, so only the overall deadline stops it. */
	@Test
	public void aSlowDownloadIsAbandonedAtTheDeadline() throws Exception {
		HttpServer server = start(LOOPBACK);
		server.createContext("/slow.zip", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				for (int i = 0; i < 100; i++) {
					body.write('x');
					body.flush();
					Thread.sleep(50);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		long started = System.nanoTime();
		assertFalse(ArchiveUrlDownloader.download(urlOf(server, "/slow.zip"), destination, limited(Long.MAX_VALUE, 500)));
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
		assertTrue("abandoned near the deadline, after " + elapsedMillis + " ms", elapsedMillis < 3000);
		assertNothingLeft();
	}

	private static final InetAddress LOOPBACK = loopback(1);
	private static final InetAddress SECOND_LOOPBACK = loopback(2);

	/** Private ranges an operator may permit for an on-premises mirror. */
	private static final List<String> PRIVATE_ADDRESSES = List.of(
			"10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1", "100.64.0.1", "100.127.255.254",
			"fc00::1", "fd12:3456::1", "fec0::1");

	/** Addresses no setting permits. */
	private static final List<String> NEVER_PERMITTED_ADDRESSES = List.of(
			"127.0.0.1", "127.1.2.3", "::1", "0.0.0.0", "0.1.2.3", "::", "169.254.169.254", "169.254.0.1",
			"fe80::1", "224.0.0.1", "ff02::1", "fd00:ec2::254", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
			"::127.0.0.1", "::169.254.169.254", "64:ff9b::a9fe:a9fe", "64:ff9b::7f00:1");

	private static Policy standard() {
		return new Policy(false, Set.of(), 5, Long.MAX_VALUE, 60_000, 5_000, 5_000);
	}

	private static Policy permitting(InetAddress loopback) {
		return new Policy(false, Set.of(loopback), 5, Long.MAX_VALUE, 60_000, 5_000, 5_000);
	}

	private static Policy limited(long maxBytes, long deadlineMillis) {
		return new Policy(false, Set.of(LOOPBACK), 5, maxBytes, deadlineMillis, 5_000, 2_000);
	}

	/** Neither the destination nor a partial file remains in its directory. */
	private void assertNothingLeft() throws Exception {
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(List.of(), files.toList());
		}
	}

	private HttpServer serveArchive(InetAddress address, String path) throws Exception {
		return serveArchive(address, path, new AtomicInteger());
	}

	private HttpServer serveArchive(InetAddress address, String path, AtomicInteger requests) throws Exception {
		HttpServer server = start(address);
		server.createContext(path, exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, ARCHIVE.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(ARCHIVE);
			}
		});
		return server;
	}

	private HttpServer redirect(InetAddress address, String path, String location) throws Exception {
		HttpServer server = start(address);
		server.createContext(path, exchange -> {
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		return server;
	}

	private HttpServer start(InetAddress address) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress(address, 0), 0);
		server.start();
		servers.add(server);
		return server;
	}

	private static URL urlOf(HttpServer server, String path) throws Exception {
		InetSocketAddress address = server.getAddress();
		return url("http://" + address.getAddress().getHostAddress() + ":" + address.getPort() + path);
	}

	private static URL url(String url) throws Exception {
		return URI.create(url).toURL();
	}

	private static InetAddress loopback(int last) {
		try {
			return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) last});
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
