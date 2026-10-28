package org.starexec.command;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.data.to.Permission;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StarexecCommand's benchmark upload against a stub server that answers as this server does.
 *
 * <p>Since 2020 the client sent the upload's file source as "localOrUrlOrGit", and the server reads
 * "localOrURLOrGit", so every upload was rejected with "Please select a file source". It also took
 * only a 302 with a New_ID cookie as success, while the asynchronous upload answers 202 with the
 * upload job's id as JSON; and viewuploadstatus asked an endpoint that knows nothing of upload jobs.
 */
public class StarexecCommandUploadTest {
	private HttpServer server;
	private String base;
	private Path archive;
	private final List<String> uploadsReceived = new ArrayList<>();
	private final List<String> requested = new ArrayList<>();

	// What the stub answers to an upload, and to a status request for upload job 17.
	private int uploadStatus = 202;
	private String uploadBody = "{\"jobId\": 17, \"status\": \"PENDING\", \"progressPercentage\": 0}";
	private String uploadCookie = null;
	private int jobStatus = 200;
	private String jobBody = "{\"id\": 17, \"status\": \"COMPLETED\", \"totalFilesFound\": 5,"
			+ " \"totalFilesProcessed\": 5, \"totalSpacesCreated\": 1}";

	@BeforeEach
	public void start() throws IOException {
		archive = Files.createTempFile("benchmarks", ".tar");
		Files.write(archive, new byte[] { 1, 2, 3 });
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/starexec/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			requested.add(path);
			int status = 404;
			String body = "";
			if (path.endsWith("secure/upload/benchmarks")) {
				uploadsReceived.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
				status = uploadStatus;
				body = uploadBody;
				if (uploadCookie != null) {
					exchange.getResponseHeaders().add("Set-Cookie", uploadCookie);
				}
			} else if (path.endsWith("services/uploads/jobs/17")) {
				status = jobStatus;
				body = jobBody;
			} else if (path.endsWith("services/benchmarks/uploadDescription/17")) {
				// An older server's answer for its own upload status ids.
				status = 200;
				body = "{\"success\": true, \"message\": \"benchmarks: 3 / 0 / 3 | spaces: 1 / 1 upload complete\"}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort() + "/starexec/";
	}

	@AfterEach
	public void stop() throws IOException {
		server.stop(0);
		Files.deleteIfExists(archive);
	}

	private int upload(Connection con) {
		return con.uploadBenchmarks(archive.toString(), 2, 5, "local", new Permission(), "", false, true, false,
				false, null);
	}

	@Test
	public void theFileSourceGoesUnderTheNameTheServerReads() {
		upload(new Connection("u", "p", base));
		assertEquals(1, uploadsReceived.size());
		String sent = uploadsReceived.get(0);
		assertTrue(sent.contains("name=\"localOrURLOrGit\""));
		assertFalse(sent.contains("name=\"localOrUrlOrGit\""));
	}

	@Test
	public void anAcceptedUploadReturnsItsUploadJob() {
		assertEquals(17, upload(new Connection("u", "p", base)));
	}

	@Test
	public void anAcceptedUploadThatNamesNoJobIsAnError() {
		uploadBody = "{\"status\": \"PENDING\"}";
		Connection con = new Connection("u", "p", base);
		assertEquals(Status.ERROR_INTERNAL, upload(con));
		assertTrue(con.getLastError().contains("did not name its upload job"), con.getLastError());
	}

	@Test
	public void anOlderServersRedirectStillWorks() {
		uploadStatus = 302;
		uploadBody = "";
		uploadCookie = "New_ID=5; Path=/starexec";
		Connection con = new Connection("u", "p", base);
		int id = upload(con);
		assertEquals(5, id, con.getLastError());
	}

	@Test
	public void viewuploadstatusDescribesTheUploadJob() {
		assertEquals("status: COMPLETED | benchmarks: 5 / 5 | spaces created: 1",
				new Connection("u", "p", base).getBenchmarkUploadStatus(17));
	}

	@Test
	public void viewuploadstatusShowsWhyAnUploadFailed() {
		jobBody = "{\"id\": 17, \"status\": \"FAILED\", \"totalFilesFound\": 2, \"totalFilesProcessed\": 1,"
				+ " \"totalSpacesCreated\": 0, \"errorMessage\": \"Quota exceeded\"}";
		assertEquals("status: FAILED | benchmarks: 1 / 2 | spaces created: 0 | Quota exceeded",
				new Connection("u", "p", base).getBenchmarkUploadStatus(17));
	}

	@Test
	public void anUploadJobThisUserCannotSeeIsNotLookedUpElsewhere() {
		// This server's answer for a job that does not exist or is not the user's
		// (RESTException.NOT_FOUND): HTTP 200, "not available". Older upload statuses are numbered
		// separately, so falling back would describe an unrelated upload with the same number.
		jobBody = "not available";
		Connection con = new Connection("u", "p", base);
		assertNull(con.getBenchmarkUploadStatus(17));
		assertEquals("No upload job 17 is visible to you", con.getLastError());
		assertFalse(requested.stream().anyMatch(p -> p.contains("uploadDescription")), requested.toString());
	}

	@Test
	public void viewuploadstatusFallsBackToAnOlderServer() {
		// An older server has no upload jobs: its id is an upload status id.
		jobStatus = 404;
		jobBody = "";
		assertEquals("benchmarks: 3 / 0 / 3 | spaces: 1 / 1 upload complete",
				new Connection("u", "p", base).getBenchmarkUploadStatus(17));
	}
}
