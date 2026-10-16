package org.starexec.command;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Four StarexecCommand calls, against a stub server that answers as this server does.
 *
 * <p>getjobpairs sent its ids as an unencoded "id[]", which Tomcat refuses with HTTP 400.
 * pushspacexml read the redirect that answers an accepted upload, with no New_ID cookie, as an
 * internal error. setspacepublic took a 200 as success, though the server answers 200 with
 * success=false when it refuses. pushsolver set= parsed the parameter's name, and sent the
 * profile as "setting" where the server reads "settingId".
 */
public class StarexecCommandContractTest {
	private HttpServer server;
	private String base;
	private Path archive;
	private final List<String> received = new ArrayList<>();

	// What the stub answers to a space XML upload, a solver upload and a visibility change.
	private int xmlStatus = 302;
	private String xmlLocation = "/starexec/secure/details/XMLuploadStatus.jsp?id=12";
	private String xmlCookie = null;
	private String xmlBody = "";
	private String visibilityBody = "{\"success\": true, \"message\": \"Space successfully made public\"}";

	@Before
	public void start() throws IOException {
		archive = Files.createTempFile("upload", ".zip");
		Files.write(archive, new byte[] { 'P', 'K', 3, 4 });
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/starexec/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			received.add(path + "\n" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
			int status = 404;
			String body = "";
			if (path.endsWith("secure/upload/space") || path.endsWith("secure/upload/jobXML")) {
				status = xmlStatus;
				body = xmlBody;
				if (xmlLocation != null) {
					exchange.getResponseHeaders().add("Location", xmlLocation);
				}
				if (xmlCookie != null) {
					exchange.getResponseHeaders().add("Set-Cookie", xmlCookie);
				}
			} else if (path.endsWith("secure/upload/solvers")) {
				status = 302;
				exchange.getResponseHeaders().add("Location", "/starexec/secure/details/solver.jsp?id=9");
				exchange.getResponseHeaders().add("Set-Cookie", "New_ID=9; Path=/starexec");
			} else if (path.contains("services/space/changePublic/")) {
				status = 200;
				body = visibilityBody;
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

	@After
	public void stop() throws IOException {
		server.stop(0);
		Files.deleteIfExists(archive);
	}

	@Test
	public void getjobpairsSendsItsIdsEncoded() {
		Map<String, String> params = new HashMap<>();
		params.put("id[]", "5,20,30");
		String url = HTMLParser.URLEncode("http://127.0.0.1/starexec/secure/download", params);
		// Tomcat refuses '[' and ']' in a query unless relaxedQueryChars allows them.
		assertEquals("http://127.0.0.1/starexec/secure/download?id%5B%5D=5%2C20%2C30", url);
	}

	@Test
	public void plainParametersAreUnchangedByTheEncoding() {
		Map<String, String> params = new HashMap<>();
		params.put("type", "jp_outputs");
		assertEquals("http://h/x?type=jp_outputs", HTMLParser.URLEncode("http://h/x", params));
	}

	@Test
	public void anAcceptedSpaceXmlUploadReturnsItsRequestId() {
		List<Integer> ids = new Connection("u", "p", base).uploadXML(archive.toString(), 3, false);
		assertEquals(Collections.singletonList(12), ids);
	}

	@Test
	public void aRedirectToTheLoginPageIsNotAnUpload() {
		// An expired session: form login answers before the upload servlet runs.
		xmlLocation = "/starexec/secure/index.jsp";
		Connection con = new Connection("u", "p", base);
		assertEquals(Collections.singletonList(Status.ERROR_SERVER), con.uploadXML(archive.toString(), 3, false));
		assertTrue(con.getLastError(), con.getLastError().contains("did not accept the upload"));
	}

	@Test
	public void theLoginPageIsNotAnUpload() {
		xmlStatus = 200;
		xmlLocation = null;
		xmlBody = "<html><head><title>Login - StarExec</title></head><body>Login</body></html>";
		assertEquals(Collections.singletonList(Status.ERROR_SERVER),
				new Connection("u", "p", base).uploadXML(archive.toString(), 3, false));
	}

	@Test
	public void aJobXmlAnswerWithoutNewJobsIsAnError() {
		// A job XML upload names its jobs in New_ID; a status-page redirect is not its answer.
		assertEquals(Collections.singletonList(Status.ERROR_SERVER),
				new Connection("u", "p", base).uploadXML(archive.toString(), 3, true));
	}

	@Test
	public void aJobXmlUploadStillReturnsItsNewJob() {
		xmlLocation = "/starexec/secure/explore/spaces.jsp";
		xmlCookie = "New_ID=7; Path=/starexec";
		assertEquals(Collections.singletonList(7), new Connection("u", "p", base).uploadXML(archive.toString(), 3, true));
	}

	@Test
	public void aRefusedVisibilityChangeIsAnError() {
		visibilityBody = "{\"success\": false, \"message\": \"You do not have permission to make this space public\"}";
		Connection con = new Connection("u", "p", base);
		assertEquals(Status.ERROR_SERVER, (int) con.setSpaceVisibility(4, true, true));
		assertEquals("You do not have permission to make this space public", con.getLastError());
	}

	@Test
	public void anAcceptedVisibilityChangeSucceeds() {
		assertEquals(0, (int) new Connection("u", "p", base).setSpaceVisibility(3, false, true));
	}

	@Test
	public void pushsolverSendsTheSettingsProfileItWasGiven() {
		Map<String, String> login = new HashMap<>();
		login.put(C.PARAM_USER, "u");
		login.put(C.PARAM_PASSWORD, "p");
		login.put(C.PARAM_BASEURL, base);
		ArgumentParser parser = new ArgumentParser(login);
		Map<String, String> push = new HashMap<>();
		push.put(C.PARAM_FILE, archive.toString());
		push.put(C.PARAM_ID, "3");
		push.put(C.PARAM_SETTING, "8");
		assertEquals(9, parser.uploadSolver(push));
		String sent = received.stream().filter(r -> r.startsWith("/starexec/secure/upload/solvers")).findFirst().orElse("");
		assertTrue(sent.contains("name=\"settingId\""));
		assertTrue(sent.contains("name=\"settingId\"\r\nContent-Type: text/plain; charset=ISO-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\n8")
				|| sent.matches("(?s).*name=\"settingId\".*?\r\n\r\n8\r\n.*"));
		assertFalse(sent.contains("name=\"setting\""));
	}
}
