package org.starexec.servlets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.starexec.constants.R;
import org.starexec.data.database.Common;
import org.starexec.data.database.JobPairs;
import org.starexec.data.database.Jobs;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.Job;
import org.starexec.data.to.JobPair;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DownloadPrivateOutputPolicyTest extends Common {
    private static final String NOT_FOUND = "Output not found.";
    private static final String INTERNAL_ERROR = "Failed to generate download";

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void deniedOutputHasTheSameResponseAsAnAbsentTarget(String type) throws Exception {
        Failure denied = reject(type, true, false, false, null);
        Failure absent = reject(type, false, false, false, null);
        assertEquals(404, denied.status());
        assertEquals(NOT_FOUND, denied.message());
        assertEquals(denied, absent);
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void databaseFailureIsGenericAndUncacheable(String type) throws Exception {
        Failure failure = reject(type, true, false, true, null);
        assertEquals(500, failure.status());
        assertEquals(INTERNAL_ERROR, failure.message());
        assertFalse(failure.cookie().contains("synthetic-database-detail"));
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void malformedIdentifierIsBadRequestWithoutLookingUpOutput(String type) throws Exception {
        Failure failure = reject(type, true, false, false, "not-an-integer");
        assertEquals(400, failure.status());
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void anAuthorizedExistingTargetPassesValidation(String type) throws Exception {
        try (Fixture fixture = new Fixture(type, true, true, false)) {
            assertTrue(Download.validateRequest(fixture.request).isSuccess());
        }
    }

    @Test
    void missingPairIsNotDereferenced() throws Exception {
        Failure failure = reject(R.PAIR_OUTPUT, false, false, false, null);
        assertEquals(404, failure.status());
        assertEquals(NOT_FOUND, failure.message());
    }

    @Test
    void bulkOutputIsRejectedBeforeAnArchiveOrExportDirectoryExists() throws Exception {
        Failure failure = reject(R.JOB_OUTPUTS, true, false, false, null);
        assertEquals(404, failure.status());
        assertEquals(NOT_FOUND, failure.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"51,", "51,-1", "0", "999999999999", ","})
    void malformedBulkIdentifiersAreBadRequests(String ids) throws Exception {
        assertEquals(400, reject(R.JOB_OUTPUTS, true, false, false, ids).status());
    }

    @ParameterizedTest
    @CsvSource({"since,not-an-integer", "since,2147483648",
            "lastTimestamp,not-a-long", "lastTimestamp,9223372036854775808"})
    void malformedIncrementalFiltersAreRejectedBeforeAuthorization(String parameter, String value) throws Exception {
        try (Fixture fixture = new Fixture(R.JOB_OUTPUT, true, true, false)) {
            when(fixture.request.getParameter(parameter)).thenReturn(value);
            fixture.common.clearInvocations();
            var status = Download.validateRequest(fixture.request);
            assertFalse(status.isSuccess());
            assertEquals(400, status.getStatusCode());
            fixture.common.verify(Common::getConnection, never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void revocationBeforeExportRetainsTheSameNotFoundPolicy(String type) throws Exception {
        Failure failure = reject(type, true, true, false, null, Recheck.REVOKED);
        assertEquals(404, failure.status());
        assertEquals(NOT_FOUND, failure.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void targetRemovalBeforeExportRetainsTheSameNotFoundPolicy(String type) throws Exception {
        Failure failure = reject(type, true, true, false, null, Recheck.REMOVED);
        assertEquals(404, failure.status());
        assertEquals(NOT_FOUND, failure.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {R.JOB_OUTPUT, R.PAIR_OUTPUT})
    void databaseFailureBeforeExportRemainsGeneric(String type) throws Exception {
        Failure failure = reject(type, true, true, false, null, Recheck.DATABASE_FAILURE);
        assertEquals(500, failure.status());
        assertEquals(INTERNAL_ERROR, failure.message());
    }

    private Failure reject(String type, boolean exists, boolean allowed, boolean sqlFailure, String id)
            throws Exception {
        return reject(type, exists, allowed, sqlFailure, id, Recheck.NONE);
    }

    private Failure reject(String type, boolean exists, boolean allowed, boolean sqlFailure, String id, Recheck recheck)
            throws Exception {
        try (Fixture fixture = new Fixture(type, exists, allowed, sqlFailure);
             MockedStatic<Download> download = mockStatic(Download.class, CALLS_REAL_METHODS)) {
            fixture.recheck = recheck;
            if (id != null) when(fixture.request.getParameter(type.equals(R.JOB_OUTPUTS) ? "id[]" : "id")).thenReturn(id);
            HttpServletResponse response = mock(HttpServletResponse.class);
            new Download().doGet(fixture.request, response);
            var status = ArgumentCaptor.forClass(Integer.class);
            var message = ArgumentCaptor.forClass(String.class);
            verify(response).sendError(status.capture(), message.capture());
            verify(response).setHeader("Cache-Control", "no-store");
            var cookie = ArgumentCaptor.forClass(Cookie.class);
            verify(response).addCookie(cookie.capture());
            assertEquals(R.STATUS_MESSAGE_COOKIE, cookie.getValue().getName());
            verify(response, never()).getOutputStream();
            if (recheck != Recheck.NONE) verify(response).reset();
            download.verify(Download::newExportDirectory, never());
            fixture.jobs.verify(() -> Jobs.getDirectory(anyInt()), never());
            return new Failure(status.getValue(), message.getValue(), cookie.getValue().getValue());
        }
    }

    private record Failure(int status, String message, String cookie) { }
    private enum Recheck { NONE, REVOKED, REMOVED, DATABASE_FAILURE }

    static final class Fixture implements AutoCloseable {
        Recheck recheck = Recheck.NONE;
        int accessReads;
        final HttpServletRequest request = mock(HttpServletRequest.class);
        final MockedStatic<SessionUtil> session = mockStatic(SessionUtil.class);
        final MockedStatic<Jobs> jobs = mockStatic(Jobs.class);
        final MockedStatic<JobPairs> pairs = mockStatic(JobPairs.class);
        final MockedStatic<Common> common = mockStatic(Common.class);
        final MockedStatic<GeneralSecurity> security = mockStatic(GeneralSecurity.class, CALLS_REAL_METHODS);

        Fixture(String type, boolean exists, boolean allowed, boolean sqlFailure) throws Exception {
            User user = new User();
            user.setId(7);
            session.when(() -> SessionUtil.getUser(request)).thenReturn(user);
            session.when(() -> SessionUtil.getUserId(request)).thenReturn(7);
            when(request.getParameter("type")).thenReturn(type);
            when(request.getParameter("id")).thenReturn(type.equals(R.JOB_OUTPUT) ? "41" : "51");
            when(request.getParameter("id[]")).thenReturn("51,52");
            Job job = new Job();
            job.setId(41);
            job.setUserId(17);
            JobPair pair = new JobPair();
            pair.setId(51);
            pair.setJobId(41);
            jobs.when(() -> Jobs.get(41)).thenReturn(exists ? job : null);
            pairs.when(() -> JobPairs.getPair(51)).thenReturn(exists ? pair : null);
            security.when(() -> GeneralSecurity.hasAdminReadPrivileges(7)).thenReturn(false);
            Connection connection = mock(Connection.class);
            common.when(Common::getConnection).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                boolean visibility = sql.contains("CanViewJob");
                if (visibility && (sqlFailure || recheck == Recheck.DATABASE_FAILURE && accessReads > 0)) {
                    throw new SQLException("synthetic-database-detail");
                }
                boolean granted = allowed && (recheck != Recheck.REVOKED || accessReads == 0);
                if (visibility) accessReads++;
                PreparedStatement statement = mock(PreparedStatement.class);
                ResultSet result = mock(ResultSet.class);
                boolean target = sql.contains("GetJobById") || sql.contains("GetJobPairById");
                when(result.next()).thenReturn(!target || exists && (recheck != Recheck.REMOVED || accessReads == 0), false);
                when(result.getInt(1)).thenReturn(sql.contains("GetJobPairById") ? 41 : 17);
                when(result.getLong(1)).thenReturn(granted && visibility ? 1L : 0L);
                when(result.getBoolean(1)).thenReturn(granted && visibility);
                when(result.getString(1)).thenReturn(R.DEFAULT_USER_ROLE_NAME);
                when(statement.executeQuery()).thenReturn(result);
                return statement;
            });
        }

        @Override
        public void close() {
            security.close();
            common.close();
            pairs.close();
            jobs.close();
            session.close();
        }
    }
}
