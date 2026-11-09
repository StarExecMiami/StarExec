package org.starexec.servlets;

import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.starexec.constants.R;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.Status;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;
import org.starexec.util.ArchiveUtil;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Tomcat HTTP responses with synthetic authentication, JDBC and completed-output fixtures. */
class DownloadPrivateOutputHttpTest {
    @TempDir
    static Path root;
    private static Tomcat server;
    private static HttpClient client;
    private static String endpoint;
    private static Path output;
    private static final String CONTENT = "synthetic completed solver output\n";

    @BeforeAll
    static void startOwnedServer() throws Exception {
        output = Files.createDirectory(root.resolve("output"));
        Files.writeString(output.resolve("result.txt"), CONTENT);
        server = new Tomcat();
        server.setBaseDir(root.resolve("server").toString());
        server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        var context = server.addContext("", Files.createDirectory(root.resolve("webapp")).toString());
        Tomcat.addServlet(context, "download", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
                String scenario = request.getParameter("fixture");
                try (var fixture = new DownloadPrivateOutputPolicyTest.Fixture(request.getParameter("type"),
                        !"missing".equals(scenario), "owner".equals(scenario) || "archive-failure".equals(scenario),
                        "database".equals(scenario));
                     var archive = "archive-failure".equals(scenario) ? mockStatic(ArchiveUtil.class, invocation -> {
                         if (invocation.getMethod().getName().equals("addDirToArchive")) {
                             throw new IOException("synthetic archive failure");
                         }
                         return invocation.callRealMethod();
                     }) : null) {
                    User user = new User();
                    user.setId(7);
                    fixture.session.when(() -> SessionUtil.getUser(request)).thenReturn(user);
                    fixture.session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
                    fixture.jobs.when(() -> Jobs.getDirectory(41)).thenReturn(output.toString());
                    JobPair pair = mock(JobPair.class);
                    when(pair.getId()).thenReturn(51);
                    when(pair.getJobId()).thenReturn(41);
                    when(pair.getBenchPath()).thenReturn("solver/default/benchmark");
                    Status status = new Status();
                    status.setCode(Status.StatusCode.STATUS_COMPLETE.getVal());
                    when(pair.getStatus()).thenReturn(status);
                    fixture.pairs.when(() -> JobPairs.getPair(51)).thenReturn("missing".equals(scenario) ? null : pair);
                    fixture.pairs.when(() -> JobPairs.getOutputPaths(pair)).thenReturn(List.of(output.toFile()));
                    new Download().service(request, response);
                } catch (Exception failure) {
                    throw new java.io.IOException(failure);
                }
            }
        });
        context.addServletMappingDecoded("/download", "download");
        server.start();
        endpoint = "http://127.0.0.1:" + server.getConnector().getLocalPort() + "/download";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopOwnedServer() throws Exception {
        if (server != null) {
            try { server.stop(); } finally { server.destroy(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT, R.JOB_OUTPUTS})
    void deniedAndAbsentTargetsHaveEqualHttpBodiesAndMessageCookies(String type) throws Exception {
        var denied = get(type, "denied", false);
        var missing = get(type, "missing", false);
        assertEquals(404, denied.statusCode());
        assertEquals(404, missing.statusCode());
        assertArrayEquals(denied.body(), missing.body());
        assertEquals(messageCookie(denied), messageCookie(missing));
        assertEquals("no-store", denied.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-store", missing.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT, R.JOB_OUTPUTS})
    void sqlErrorsRemainGenericOverHttp(String type) throws Exception {
        var response = get(type, "database", false);
        assertEquals(500, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("Failed to generate download"));
        assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("synthetic-database-detail"));
        assertFalse(messageCookie(response).contains("synthetic-database-detail"));
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT, R.JOB_OUTPUTS})
    void successfulArchivesPreserveTheCompletedOutput(String type) throws Exception {
        var response = get(type, "owner", false);
        assertEquals(200, response.statusCode());
        boolean found = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith("result.txt")) {
                    assertEquals(CONTENT, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    found = true;
                }
            }
        }
        assertTrue(found, "The archive must contain the original output");
        assertEquals(CONTENT, Files.readString(output.resolve("result.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT, R.JOB_OUTPUTS})
    void malformedIdsRemainBadRequestsOverHttp(String type) throws Exception {
        var response = get(type, "owner", true);
        assertEquals(400, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test
    void anonymousOutputLinksKeepTheirExistingForbiddenResponse() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?type=" + R.JOB_OUTPUT + "&anonId=synthetic"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        assertEquals(403, client.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void postKeepsItsExistingMethodRejection() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?type=" + R.JOB_OUTPUT))
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(405, client.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test
    void mixedAnonymousAndSequentialIdsKeepTheirBadRequestResponse() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?type=" + R.JOB_OUTPUT + "&id=41&anonId=synthetic"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(400, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("cannot combine anonId with sequential id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {R.PAIR_OUTPUT, R.JOB_OUTPUTS})
    void archivePreparationFailureIsNotReportedAsASuccessfulDownload(String type) throws Exception {
        var response = get(type, "archive-failure", false);
        assertEquals(500, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("Failed to generate download"));
        assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("synthetic archive failure"));
        assertEquals(CONTENT, Files.readString(output.resolve("result.txt")));
    }

    private static HttpResponse<byte[]> get(String type, String scenario, boolean malformed) throws Exception {
        String id = malformed ? "invalid" : type.equals(R.JOB_OUTPUT) ? "41" : "51";
        String parameter = type.equals(R.JOB_OUTPUTS) ? "id%5B%5D" : "id";
        var request = HttpRequest.newBuilder(URI.create(endpoint + "?type=" + type + "&" + parameter + "=" + id
                + "&fixture=" + scenario)).timeout(Duration.ofSeconds(10)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String messageCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(R.STATUS_MESSAGE_COOKIE + "="))
                .findFirst().orElseThrow();
    }
}
