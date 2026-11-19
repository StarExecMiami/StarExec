package org.starexec.data.database;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.starexec.data.to.Permission;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.UploadSession;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The permissions chosen on the benchmark upload form travel with the upload, as
 * upload_jobs.space_permissions and upload_sessions.space_permissions (V0120), to the worker that
 * creates the spaces. Until then they were dropped, and every created space got the same five
 * "add" permissions.
 */
public class UploadJobSpacePermissionsTest {
    private static final List<String> NAMES = Arrays.asList(
        "addSolver", "addBench", "addUser", "addSpace", "addJob",
        "removeSolver", "removeBench", "removeUser", "removeSpace", "removeJob");

    private static Permission granting(String... names) {
        Permission p = new Permission();
        for (String name : names) {
            p.setPermissionOn(name);
        }
        return p;
    }

    private static List<String> on(Permission p) {
        List<String> names = new ArrayList<>(p.getOnPermissions());
        Collections.sort(names);
        return names;
    }

    private static UploadJob.UploadJobRequest request(Permission permissions) {
        return new UploadJob.UploadJobRequest.Builder()
            .archivePath("/app/data/Benchmarks/7/20261016-17.00.00.000/Problems.tgz")
            .userId(7)
            .spaceId(10)
            .spacePermissions(permissions)
            .build();
    }

    private static void bind(PreparedStatement ps, UploadJob.UploadJobRequest request) throws Exception {
        Method m = UploadJobQueue.class.getDeclaredMethod(
            "bindUploadJobRequest", PreparedStatement.class, UploadJob.UploadJobRequest.class);
        m.setAccessible(true);
        m.invoke(null, ps, request);
    }

    @Test
    public void theChoiceIsStoredAsTheGrantedNamesSorted() {
        assertEquals("addBench,removeSolver",
            UploadJob.encodeSpacePermissions(granting("removeSolver", "addBench")));
    }

    @Test
    public void grantingNothingIsStoredAsEmptyNotAsAbsent() {
        // Absent (null) would mean "the old default", which grants the five "add" permissions.
        assertEquals("", UploadJob.encodeSpacePermissions(new Permission()));
        assertEquals("", UploadJob.encodeSpacePermissionNames(Collections.emptyList()));
    }

    @Test
    public void eachPermissionSurvivesTheRoundTripAlone() {
        for (String name : NAMES) {
            Permission back = UploadJob.decodeSpacePermissions(
                UploadJob.encodeSpacePermissions(granting(name)));
            assertEquals(Collections.singletonList(name), on(back), name);
        }
    }

    @Test
    public void aNameThatIsNotAPermissionGrantsNothing() {
        assertEquals("addBench",
            UploadJob.encodeSpacePermissionNames(Arrays.asList("addBench", "dropTables", null)));
    }

    @Test
    public void nothingSentMeansTheOldDefault() {
        assertNull(UploadJob.encodeSpacePermissions(null));
        assertNull(UploadJob.encodeSpacePermissionNames(null));
        assertNull(UploadJob.decodeSpacePermissions(null));
    }

    @Test
    public void theQueueWritesThemWithTheJob() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        bind(ps, request(granting("addBench", "removeSolver")));
        Mockito.verify(ps).setString(14, "addBench,removeSolver");
    }

    @Test
    public void aRequestWithoutThemWritesNull() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        bind(ps, request(null));
        Mockito.verify(ps).setString(14, null);
    }

    @Test
    public void theClaimedJobCarriesThem() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("space_permissions")).thenReturn("addBench,removeSolver");
        Method map = UploadJobQueue.class.getDeclaredMethod("mapResultSetToJob", ResultSet.class);
        map.setAccessible(true);
        UploadJob job = (UploadJob) map.invoke(null, rs);
        assertEquals(Arrays.asList("addBench", "removeSolver"), on(job.getSpacePermissions()));
    }

    @Test
    public void aJobQueuedBeforeV0120ReadsAsNone() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Method map = UploadJobQueue.class.getDeclaredMethod("mapResultSetToJob", ResultSet.class);
        map.setAccessible(true);
        assertNull(((UploadJob) map.invoke(null, rs)).getSpacePermissions());
    }

    @Test
    public void aClaimedJobStillMapsBeforeTheMigrationHasRun() throws Exception {
        // Jenkins starts the new application before it applies the migrations. The claim has
        // already marked the row PROCESSING, so a mapping failure would strand it.
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getLong("id")).thenReturn(42L);
        Mockito.when(rs.getString("source_url"))
            .thenThrow(new SQLException("column \"source_url\" does not exist", "42703"));
        Mockito.when(rs.getString("space_permissions"))
            .thenThrow(new SQLException("column \"space_permissions\" does not exist", "42703"));
        Method map = UploadJobQueue.class.getDeclaredMethod("mapResultSetToJob", ResultSet.class);
        map.setAccessible(true);
        UploadJob job = (UploadJob) map.invoke(null, rs);
        assertEquals(42L, job.getId());
        assertNull(job.getSourceUrl());
        assertNull(job.getSpacePermissions(), "the old default applies");
    }

    @Test
    public void aSessionStillMapsBeforeTheMigrationHasRun() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("space_permissions"))
            .thenThrow(new SQLException("column \"space_permissions\" does not exist", "42703"));
        Method map = UploadSessions.class.getDeclaredMethod("mapResultSet", ResultSet.class);
        map.setAccessible(true);
        assertNull(((UploadSession) map.invoke(null, rs)).getSpacePermissions());
    }

    @Test
    public void anyOtherDatabaseFailureStillPropagates() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("space_permissions")).thenThrow(new SQLException("connection lost", "08006"));
        try {
            UploadJobQueue.columnAddedByMigration(rs, "space_permissions");
            fail("a lost connection is not a missing column");
        } catch (SQLException e) {
            assertEquals("08006", e.getSQLState());
        }
    }

    @Test
    public void aSessionCarriesThemToItsJob() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("space_permissions")).thenReturn("removeJob");
        Method map = UploadSessions.class.getDeclaredMethod("mapResultSet", ResultSet.class);
        map.setAccessible(true);
        UploadSession session = (UploadSession) map.invoke(null, rs);
        assertEquals("removeJob", session.getSpacePermissions());
        // RESTServices.finalizeUploadSession hands the job exactly this, decoded.
        assertEquals(Collections.singletonList("removeJob"),
            on(UploadJob.decodeSpacePermissions(session.getSpacePermissions())));
    }
}
