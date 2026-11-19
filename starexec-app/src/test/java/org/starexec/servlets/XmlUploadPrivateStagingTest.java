package org.starexec.servlets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.starexec.config.EnvironmentConfig;
import org.starexec.constants.R;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Uploads;
import org.starexec.data.to.Permission;
import org.starexec.util.BatchUtil;
import org.starexec.util.JobUtil;
import org.starexec.util.PartWrapper;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class XmlUploadPrivateStagingTest {
    private static final int OWNER = 5;
    private static final int SPACE = 3;
    private static final int STATUS = 11;
    @TempDir Path files;
    private Path privateRoot;
    private Path runner;
    private File seen;
    private boolean configured = true;
    private final HashMap<String, Object> form = new HashMap<>();

    @BeforeAll static void patterns() { Validator.initialize(); }

    @BeforeEach void setup() throws Exception {
        privateRoot = Files.createDirectory(files.resolve("private"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        runner = Files.createDirectory(files.resolve("runner"));
    }

    private PartWrapper archive() throws Exception {
        PartWrapper part = mock(PartWrapper.class);
        when(part.getName()).thenReturn("input.zip");
        doAnswer(invocation -> {
            File destination = invocation.getArgument(0);
            try (var out = new ZipOutputStream(Files.newOutputStream(destination.toPath()))) {
                out.putNextEntry(new ZipEntry("input.xml"));
                out.write("<synthetic/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            return null;
        }).when(part).write(any(File.class));
        return part;
    }

    private List<Integer> read(File xml) throws IOException {
        seen = xml;
        assertEquals("<synthetic/>", Files.readString(xml.toPath()));
        if (configured) {
            assertTrue(xml.toPath().startsWith(privateRoot), "XML input must stay in private upload storage");
            assertFalse(xml.toPath().startsWith(runner));
        }
        return List.of(19);
    }

    private interface Body { void run() throws Exception; }

    private void boundaries(Body body) throws Exception {
        try (var config = mockStatic(EnvironmentConfig.class, CALLS_REAL_METHODS);
             var r = mockStatic(R.class, CALLS_REAL_METHODS);
             var util = mockStatic(Util.class, CALLS_REAL_METHODS);
             var session = mockStatic(SessionUtil.class);
             var permissions = mockStatic(Permissions.class);
             var uploads = mockStatic(Uploads.class)) {
            config.when(EnvironmentConfig::getUploadStagingDirectory).thenReturn(configured ? privateRoot.toString() : "");
            config.when(EnvironmentConfig::getDataDir).thenReturn(runner.toString());
            config.when(EnvironmentConfig::getJobOutputDirectory).thenReturn(runner.resolve("output").toString());
            config.when(EnvironmentConfig::getJobLogDirectory).thenReturn(runner.resolve("logs").toString());
            config.when(EnvironmentConfig::getBuildArtifactDirectory).thenReturn("");
            r.when(R::getBatchSpaceXMLDir).thenReturn(runner.resolve("batchSpace/uploads").toString());
            util.when(() -> Util.parseMultipartRequest(any(HttpServletRequest.class))).thenReturn(form);
            util.when(() -> Util.threadPoolExecute(any(Runnable.class))).thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            });
            session.when(() -> SessionUtil.getUserId(any(HttpServletRequest.class))).thenReturn(OWNER);
            permissions.when(() -> Permissions.get(OWNER, SPACE)).thenReturn(new Permission(true));
            body.run();
        }
    }

    private HttpServletResponse job(PartWrapper part) throws Exception {
        form.put("f", part);
        form.put(R.SPACE, String.valueOf(SPACE));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=synthetic");
        HttpServletResponse response = mock(HttpServletResponse.class);
        boundaries(() -> {
            try (var constructors = mockConstruction(JobUtil.class, (job, context) -> {
                when(job.createJobsFromFile(any(File.class), eq(OWNER), eq(SPACE), any(), any()))
                    .thenAnswer(invocation -> read(invocation.getArgument(0)));
                when(job.getJobCreationSuccess()).thenReturn(true);
            })) {
                new UploadJobXML().doPost(request, response);
            }
        });
        return response;
    }

    @Test void jobImportReadsPrivateContentAndRemovesItsClaim() throws Exception {
        job(archive());
        assertNotNull(seen);
        assertFalse(seen.exists());
        try (var claims = Files.list(privateRoot)) { assertEquals(0, claims.count()); }
    }

    @Test void spaceImportReadsPrivateContentAndRemovesItsClaim() throws Exception {
        form.put("f", archive());
        BatchUtil batch = mock(BatchUtil.class);
        when(batch.createSpacesFromFile(any(File.class), eq(OWNER), eq(SPACE), eq(STATUS)))
            .thenAnswer(invocation -> read(invocation.getArgument(0)));
        boundaries(() -> new UploadSpaceXML().handleXMLFile(OWNER, SPACE, form, batch, STATUS));
        assertNotNull(seen);
        assertFalse(seen.exists());
        try (var claims = Files.list(privateRoot)) { assertEquals(0, claims.count()); }
    }

    @Test void missingJobStorageRefusesBeforeWritingAnyArchive() throws Exception {
        configured = false;
        PartWrapper part = archive();
        HttpServletResponse response = job(part);
        verify(part, never()).write(any(File.class));
        verify(response).sendError(eq(HttpServletResponse.SC_INTERNAL_SERVER_ERROR), anyString());
    }

    @Test void missingSpaceStorageRefusesBeforeWritingAnyArchive() throws Exception {
        configured = false;
        PartWrapper part = archive();
        form.put("f", part);
        BatchUtil batch = mock(BatchUtil.class);
        boundaries(() -> new UploadSpaceXML().handleXMLFile(OWNER, SPACE, form, batch, STATUS));
        verify(part, never()).write(any(File.class));
        verifyNoInteractions(batch);
    }
}
