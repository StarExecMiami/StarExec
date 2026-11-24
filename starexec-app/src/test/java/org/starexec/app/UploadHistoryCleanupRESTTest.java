package org.starexec.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.database.Uploads;
import org.starexec.data.database.Users;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.BenchmarkUploadStatus;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.User;
import org.starexec.util.CsrfUtil;
import org.starexec.util.DataTablesQuery;
import org.starexec.util.SessionUtil;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies upload history token disambiguation, lifecycle guards, CSRF protection,
 * and authorization on /services/delete/upload and /services/clear/uploads/{userId}.
 */
public class UploadHistoryCleanupRESTTest {

    private static final Gson gson = new GsonBuilder().create();

    @Test
    public void convertUploadsToJsonObjectDisambiguatesAsyncAndLegacyCollisions() {
        Timestamp now = Timestamp.from(Instant.now());

        BenchmarkUploadStatus legacy = new BenchmarkUploadStatus();
        legacy.setId(7);
        legacy.setUserId(10);
        legacy.setUploadDate(now);
        legacy.setTotalBenchmarks(12);
        legacy.setEverythingComplete(true);
        legacy.setAsyncUpload(false);

        BenchmarkUploadStatus async = new BenchmarkUploadStatus();
        async.setId(7);
        async.setUserId(10);
        async.setUploadDate(now);
        async.setTotalBenchmarks(0);
        async.setEverythingComplete(false);
        async.setAsyncUpload(true);

        DataTablesQuery query = new DataTablesQuery(2, 2, 1);

        JsonObject result = RESTHelpers.convertUploadsToJsonObject(List.of(legacy, async), query);
        JsonArray rows = result.getAsJsonArray("aaData");
        assertEquals(2, rows.size());

        String legacyCell = rows.get(0).getAsJsonArray().get(0).getAsString();
        String asyncCell = rows.get(1).getAsJsonArray().get(0).getAsString();
        assertTrue(legacyCell.contains("value =\"legacy:7\""), legacyCell);
        assertTrue(legacyCell.contains("data-upload-kind=\"legacy\""), legacyCell);
        assertTrue(asyncCell.contains("value =\"async:7\""), asyncCell);
        assertTrue(asyncCell.contains("data-upload-kind=\"async\""), asyncCell);
    }

    @Test
    public void deleteUploadsRoutesDisambiguatedAsyncAndLegacyTokensWithoutCollision() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterValues("selectedIds[]")).thenReturn(new String[]{"async:7", "legacy:7"});

        UploadJob asyncJob = uploadJob(7L, 10, "FAILED");
        BenchmarkUploadStatus legacyStatus = legacyUpload(7, 10, false, Instant.now().minus(2, ChronoUnit.HOURS));

        try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
             MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
            sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(10);
            queue.when(() -> UploadJobQueue.getJob(7L)).thenReturn(Optional.of(asyncJob));
            uploads.when(() -> Uploads.getBenchmarkStatus(7)).thenReturn(legacyStatus);
            uploads.when(() -> Uploads.isLegacyUploadEligibleForDeletion(legacyStatus)).thenCallRealMethod();
            queue.when(() -> UploadJobQueue.deleteTerminalJob(7L)).thenReturn(UploadJobQueue.DeleteOutcome.DELETED);
            uploads.when(() -> Uploads.deleteLegacyUpload(7)).thenReturn(true);

            ValidatorStatusCode status = gson.fromJson(new RESTServices().deleteUploads(request), ValidatorStatusCode.class);

            assertTrue(status.isSuccess());
            queue.verify(() -> UploadJobQueue.deleteTerminalJob(7L));
            uploads.verify(() -> Uploads.deleteLegacyUpload(7));
        }
    }

    @Test
    public void deleteUploadsRejectsActiveJobsAtomicallyBeforeDeletingValidSiblings() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterValues("selectedIds[]")).thenReturn(new String[]{"legacy:7", "async:9"});

        BenchmarkUploadStatus completedLegacy = legacyUpload(7, 10, true, Instant.now());
        UploadJob processingJob = uploadJob(9L, 10, "PROCESSING");

        try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
             MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
            sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(10);
            uploads.when(() -> Uploads.getBenchmarkStatus(7)).thenReturn(completedLegacy);
            uploads.when(() -> Uploads.isLegacyUploadEligibleForDeletion(completedLegacy)).thenCallRealMethod();
            queue.when(() -> UploadJobQueue.getJob(9L)).thenReturn(Optional.of(processingJob));

            ValidatorStatusCode status = gson.fromJson(new RESTServices().deleteUploads(request), ValidatorStatusCode.class);

            assertFalse(status.isSuccess());
            assertTrue(status.getMessage().contains("active upload job"));
            uploads.verify(() -> Uploads.deleteLegacyUpload(Mockito.anyInt()), Mockito.never());
            queue.verify(() -> UploadJobQueue.deleteTerminalJob(Mockito.anyLong()), Mockito.never());
        }
    }

    @Test
    public void deleteUploadsRejectsFreshIncompleteLegacyUploadUnderOneHour() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterValues("selectedIds[]")).thenReturn(new String[]{"legacy:15"});

        BenchmarkUploadStatus freshIncomplete = legacyUpload(15, 10, false, Instant.now().minus(20, ChronoUnit.MINUTES));

        try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
            sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(10);
            uploads.when(() -> Uploads.getBenchmarkStatus(15)).thenReturn(freshIncomplete);
            uploads.when(() -> Uploads.isLegacyUploadEligibleForDeletion(freshIncomplete)).thenCallRealMethod();

            ValidatorStatusCode status = gson.fromJson(new RESTServices().deleteUploads(request), ValidatorStatusCode.class);

            assertFalse(status.isSuccess());
            assertTrue(status.getMessage().contains("1 hour"));
            uploads.verify(() -> Uploads.deleteLegacyUpload(Mockito.anyInt()), Mockito.never());
        }
    }

    @Test
    public void deleteUploadsEnforcesOwnerOrAdminAuthorizationAndInvalidTokens() {
        HttpServletRequest unauthorizedReq = mock(HttpServletRequest.class);
        when(unauthorizedReq.getParameterValues("selectedIds[]")).thenReturn(new String[]{"async:12"});
        UploadJob othersJob = uploadJob(12L, 99, "COMPLETED");

        try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
             MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class)) {
            sessionUtil.when(() -> SessionUtil.getUserId(unauthorizedReq)).thenReturn(10);
            queue.when(() -> UploadJobQueue.getJob(12L)).thenReturn(Optional.of(othersJob));
            general.when(() -> GeneralSecurity.hasAdminWritePrivileges(10)).thenReturn(false);

            ValidatorStatusCode denied = gson.fromJson(new RESTServices().deleteUploads(unauthorizedReq), ValidatorStatusCode.class);
            assertFalse(denied.isSuccess());
            queue.verify(() -> UploadJobQueue.deleteTerminalJob(Mockito.anyLong()), Mockito.never());

            general.when(() -> GeneralSecurity.hasAdminWritePrivileges(10)).thenReturn(true);
            queue.when(() -> UploadJobQueue.deleteTerminalJob(12L)).thenReturn(UploadJobQueue.DeleteOutcome.DELETED);
            ValidatorStatusCode adminAllowed = gson.fromJson(new RESTServices().deleteUploads(unauthorizedReq), ValidatorStatusCode.class);
            assertTrue(adminAllowed.isSuccess());
        }

        for (String badToken : List.of("7", "other:7", "async:-1", "legacy:abc")) {
            HttpServletRequest badReq = mock(HttpServletRequest.class);
            when(badReq.getParameterValues("selectedIds[]")).thenReturn(new String[]{badToken});
            try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
                 MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
                 MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
                sessionUtil.when(() -> SessionUtil.getUserId(badReq)).thenReturn(10);
                ValidatorStatusCode invalid = gson.fromJson(new RESTServices().deleteUploads(badReq), ValidatorStatusCode.class);
                assertFalse(invalid.isSuccess(), "Expected invalid token to fail: " + badToken);
                queue.verifyNoInteractions();
                uploads.verifyNoInteractions();
            }
        }
    }

    @Test
    public void deleteUploadsReportsPvcCleanupFailureWithoutFakeSuccess() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameterValues("selectedIds[]")).thenReturn(new String[]{"async:21"});
        UploadJob job = uploadJob(21L, 10, "FAILED");

        try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class)) {
            sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(10);
            queue.when(() -> UploadJobQueue.getJob(21L)).thenReturn(Optional.of(job));
            queue.when(() -> UploadJobQueue.deleteTerminalJob(21L)).thenReturn(UploadJobQueue.DeleteOutcome.PVC_CLEANUP_FAILED);

            ValidatorStatusCode status = gson.fromJson(new RESTServices().deleteUploads(request), ValidatorStatusCode.class);
            assertFalse(status.isSuccess());
            assertTrue(status.getMessage().contains("preserved"));
        }
    }

    @Test
    public void clearUploadHistoryChecksAuthorizationAndAggregatesResults() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        User target = new User();
        target.setId(10);

        try (MockedStatic<SessionUtil> sessionUtil = Mockito.mockStatic(SessionUtil.class);
             MockedStatic<Users> users = Mockito.mockStatic(Users.class);
             MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);
             MockedStatic<UploadJobQueue> queue = Mockito.mockStatic(UploadJobQueue.class);
             MockedStatic<Uploads> uploads = Mockito.mockStatic(Uploads.class)) {
            users.when(() -> Users.get(10)).thenReturn(target);

            sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(99);
            general.when(() -> GeneralSecurity.hasAdminWritePrivileges(99)).thenReturn(false);
            ValidatorStatusCode denied = gson.fromJson(new RESTServices().clearUploadHistory(10, request), ValidatorStatusCode.class);
            assertFalse(denied.isSuccess());
            queue.verifyNoInteractions();
            uploads.verifyNoInteractions();

            sessionUtil.when(() -> SessionUtil.getUserId(request)).thenReturn(10);
            queue.when(() -> UploadJobQueue.clearTerminalJobsForUser(10))
                .thenReturn(new UploadJobQueue.ClearUserJobsResult(2, 0));
            uploads.when(() -> Uploads.clearEligibleLegacyUploadsForUser(10)).thenReturn(3);
            ValidatorStatusCode cleared = gson.fromJson(new RESTServices().clearUploadHistory(10, request), ValidatorStatusCode.class);
            assertTrue(cleared.isSuccess());
            assertTrue(cleared.getMessage().contains("5"));

            queue.when(() -> UploadJobQueue.clearTerminalJobsForUser(10))
                .thenReturn(new UploadJobQueue.ClearUserJobsResult(1, 1));
            ValidatorStatusCode partialPvcFailure = gson.fromJson(new RESTServices().clearUploadHistory(10, request), ValidatorStatusCode.class);
            assertFalse(partialPvcFailure.isSuccess());
            assertTrue(partialPvcFailure.getMessage().contains("1 entry(s) could not be deleted"));
        }
    }

    @Test
    public void csrfFilterProtectsUploadHistoryCleanupEndpoints() throws Exception {
        CsrfFilter filter = new CsrfFilter();
        for (String path : List.of("/services/delete/upload", "/services/clear/uploads/10")) {
            HttpServletRequest unauthenticatedPost = mock(HttpServletRequest.class);
            HttpServletResponse forbiddenResponse = mock(HttpServletResponse.class);
            FilterChain blockedChain = mock(FilterChain.class);
            when(unauthenticatedPost.getMethod()).thenReturn("POST");
            when(unauthenticatedPost.getServletPath()).thenReturn(path);

            filter.doFilter(unauthenticatedPost, forbiddenResponse, blockedChain);

            verify(forbiddenResponse).sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or missing CSRF token");
            Mockito.verifyNoInteractions(blockedChain);

            HttpServletRequest validPost = mock(HttpServletRequest.class);
            HttpServletResponse okResponse = mock(HttpServletResponse.class);
            FilterChain allowedChain = mock(FilterChain.class);
            HttpSession session = mock(HttpSession.class);
            when(validPost.getMethod()).thenReturn("POST");
            when(validPost.getServletPath()).thenReturn(path);
            when(validPost.getSession(false)).thenReturn(session);
            when(session.getAttribute(CsrfUtil.CSRF_TOKEN_ATTR)).thenReturn("csrf-secret-token");
            when(validPost.getHeader(CsrfFilter.CSRF_HEADER)).thenReturn("csrf-secret-token");

            filter.doFilter(validPost, okResponse, allowedChain);

            verify(allowedChain).doFilter(validPost, okResponse);
        }
    }

    private static UploadJob uploadJob(long id, int userId, String status) {
        UploadJob job = new UploadJob();
        job.setId(id);
        job.setUserId(userId);
        job.setStatus(status);
        return job;
    }

    private static BenchmarkUploadStatus legacyUpload(int id, int userId, boolean complete, Instant uploadTime) {
        BenchmarkUploadStatus status = new BenchmarkUploadStatus();
        status.setId(id);
        status.setUserId(userId);
        status.setEverythingComplete(complete);
        status.setUploadDate(Timestamp.from(uploadTime));
        return status;
    }
}
