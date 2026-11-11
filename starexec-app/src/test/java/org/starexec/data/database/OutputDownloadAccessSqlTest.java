package org.starexec.data.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.starexec.constants.R;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.test.util.DatabaseTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.starexec.data.database.Permissions.OutputAccess.*;

/** Checked download authorization against a disposable PostgreSQL database. */
class OutputDownloadAccessSqlTest extends Common {
    private final List<Integer> users = new ArrayList<>();
    private final List<Integer> jobs = new ArrayList<>();
    private final List<Integer> spaces = new ArrayList<>();
    private int owner;
    private int viewer;
    private int job;
    private int pair;

    @BeforeAll
    static void requireDatabase() {
        DatabaseTestSupport.assumeDatabaseAvailable("OutputDownloadAccessSqlTest");
        Common.initialize();
    }

    @BeforeEach
    void fixture() throws Exception {
        owner = user(R.DEFAULT_USER_ROLE_NAME);
        viewer = user(R.DEFAULT_USER_ROLE_NAME);
        job = job(owner);
        pair = pair(job);
    }

    @AfterEach
    void cleanupOwnedRows() throws Exception {
        try (Connection con = Common.getConnection()) {
            for (int id : jobs) update(con, "DELETE FROM starexec.jobs WHERE id = ?", id);
            for (int id : spaces) update(con, "DELETE FROM starexec.spaces WHERE id = ?", id);
            for (int id : users) {
                update(con, "DELETE FROM starexec.user_roles WHERE email = (SELECT email FROM starexec.users WHERE id = ?)", id);
                update(con, "DELETE FROM starexec.users WHERE id = ?", id);
                Users.invalidateIsAdminCache(id);
            }
        }
    }

    @Test
    void privateOwnerAndOutsiderKeepTheirExistingPermissions() throws Exception {
        assertEquals(ALLOWED, Permissions.checkJobOutputAccess(job, owner));
        assertEquals(FORBIDDEN, Permissions.checkJobOutputAccess(job, viewer));
        assertEquals(ALLOWED, Permissions.checkPairOutputsAccess(List.of(pair), owner));
        assertEquals(FORBIDDEN, Permissions.checkPairOutputsAccess(List.of(pair), viewer));
    }

    @Test
    void absentAndDeletedJobsRemainAbsentEvenForAnAdministrator() throws Exception {
        int administrator = user(R.ADMIN_ROLE_NAME);
        assertEquals(NOT_FOUND, Permissions.checkJobOutputAccess(Integer.MAX_VALUE, administrator));
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.jobs SET deleted = true WHERE id = ?", job);
        }
        assertEquals(NOT_FOUND, Permissions.checkJobOutputAccess(job, administrator));
        assertEquals(NOT_FOUND, Permissions.checkPairOutputsAccess(List.of(pair), administrator));
    }

    @Test
    void missingPairIsDistinctFromADeniedExistingPair() throws Exception {
        assertEquals(NOT_FOUND, Permissions.checkPairOutputsAccess(List.of(Integer.MAX_VALUE), viewer));
        assertEquals(FORBIDDEN, Permissions.checkPairOutputsAccess(List.of(pair), viewer));
    }

    @Test
    void administratorsAndDevelopersKeepReadAccess() throws Exception {
        for (String role : List.of(R.ADMIN_ROLE_NAME, R.DEVELOPER_ROLE_NAME)) {
            int privileged = user(role);
            assertEquals(ALLOWED, Permissions.checkJobOutputAccess(job, privileged));
            assertEquals(ALLOWED, Permissions.checkPairOutputsAccess(List.of(pair), privileged));
        }
    }

    @Test
    void publicSpaceGrantsAndRevocationsUseTheExistingFunction() throws Exception {
        int space = space(true);
        associate(space);
        assertEquals(ALLOWED, Permissions.checkJobOutputAccess(job, viewer));
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.spaces SET public_access = false WHERE id = ?", space);
        }
        assertEquals(FORBIDDEN, Permissions.checkJobOutputAccess(job, viewer));
    }

    @Test
    void sharedSpaceMembershipAndRemovalUseTheExistingAcl() throws Exception {
        int space = space(false);
        associate(space);
        try (Connection con = Common.getConnection()) {
            update(con, "INSERT INTO starexec.user_assoc(user_id, space_id) VALUES (?, ?)", viewer, space);
        }
        assertEquals(ALLOWED, Permissions.checkJobOutputAccess(job, viewer));
        try (Connection con = Common.getConnection()) {
            update(con, "DELETE FROM starexec.user_assoc WHERE user_id = ? AND space_id = ?", viewer, space);
        }
        assertEquals(FORBIDDEN, Permissions.checkJobOutputAccess(job, viewer));
    }

    @Test
    void publicUserOwnedJobsRemainPublic() throws Exception {
        int previous = R.PUBLIC_USER_ID;
        int publicUser = user(R.DEFAULT_USER_ROLE_NAME);
        try {
            R.PUBLIC_USER_ID = publicUser;
            assertEquals(ALLOWED, Permissions.checkJobOutputAccess(job(publicUser), viewer));
        } finally {
            R.PUBLIC_USER_ID = previous;
        }
    }

    @Test
    void bulkPairsMustHaveOneVisibleParent() throws Exception {
        int second = pair(job);
        assertEquals(ALLOWED, Permissions.checkPairOutputsAccess(List.of(pair, second), owner));
        int other = pair(job(owner));
        assertEquals(INVALID_REQUEST, Permissions.checkPairOutputsAccess(List.of(pair, other), owner));
        int hidden = pair(job(viewer));
        assertEquals(FORBIDDEN, Permissions.checkPairOutputsAccess(List.of(pair, hidden), owner));
    }

    @Test
    void malformedBulkIdsNeverReachTheDatabase() throws Exception {
        assertEquals(INVALID_REQUEST, Permissions.checkPairOutputsAccess(List.of(), viewer));
        assertEquals(INVALID_REQUEST, Permissions.checkPairOutputsAccess(List.of(0), viewer));
        assertEquals(INVALID_REQUEST, Permissions.checkPairOutputsAccess(List.of(-1), viewer));
    }

    @Test
    void duplicateRolesFailRatherThanSelectingAnArbitraryPrivilege() throws Exception {
        try (Connection con = Common.getConnection()) {
            update(con, "INSERT INTO starexec.user_roles(email, role) SELECT email, ? FROM starexec.users WHERE id = ?",
                    R.ADMIN_ROLE_NAME, viewer);
        }
        assertThrows(SQLException.class, () -> Permissions.checkJobOutputAccess(job, viewer));
    }

    @Test
    void cachedAdministrativePrivilegesCannotOverrideTheCurrentDownloadRole() throws Exception {
        int administrator = user(R.ADMIN_ROLE_NAME);
        assertTrue(GeneralSecurity.hasAdminReadPrivileges(administrator));
        try (Connection con = Common.getConnection()) {
            update(con, "UPDATE starexec.user_roles SET role = ? WHERE email = (SELECT email FROM starexec.users WHERE id = ?)",
                    R.DEFAULT_USER_ROLE_NAME, administrator);
        }
        assertEquals(FORBIDDEN, Permissions.checkJobOutputAccess(job, administrator));
    }

    @Test
    void closedDatabaseConnectionIsNotMisclassifiedAsAbsence() throws Exception {
        Connection closed = Common.getConnection();
        closed.close();
        try (var common = mockStatic(Common.class, CALLS_REAL_METHODS)) {
            common.when(Common::getConnection).thenReturn(closed);
            assertThrows(SQLException.class, () -> Permissions.checkJobOutputAccess(job, viewer));
            assertThrows(SQLException.class, () -> Permissions.checkPairOutputsAccess(List.of(pair), viewer));
        }
    }

    private int user(String role) throws Exception {
        String email = "download-" + UUID.randomUUID() + "@example.invalid";
        try (Connection con = Common.getConnection()) {
            int id = insert(con, "INSERT INTO starexec.users(email, first_name, last_name, institution, created, password, disk_quota)"
                    + " VALUES (?, 'Download', 'Fixture', 'synthetic', now(), 'synthetic', 67108864) RETURNING id", email);
            users.add(id);
            update(con, "INSERT INTO starexec.user_roles(email, role) VALUES (?, ?)", email, role);
            return id;
        }
    }

    private int job(int user) throws Exception {
        try (Connection con = Common.getConnection()) {
            int id = insert(con, "INSERT INTO starexec.jobs(user_id, name, buildjob, total_pairs, disk_size)"
                    + " VALUES (?, 'download-fixture', false, 1, 0) RETURNING id", user);
            jobs.add(id);
            return id;
        }
    }

    private int pair(int parent) throws Exception {
        try (Connection con = Common.getConnection()) {
            int id = insert(con, "INSERT INTO starexec.job_pairs(job_id, primary_jobpair_data) VALUES (?, 1) RETURNING id", parent);
            update(con, "INSERT INTO starexec.jobpair_stage_data(jobpair_id, stage_number, disk_size) VALUES (?, 1, 0)", id);
            return id;
        }
    }

    private int space(boolean publicAccess) throws Exception {
        try (Connection con = Common.getConnection()) {
            int id = insert(con, "INSERT INTO starexec.spaces(name, public_access) VALUES ('download-fixture', ?) RETURNING id", publicAccess);
            spaces.add(id);
            return id;
        }
    }

    private void associate(int space) throws Exception {
        try (Connection con = Common.getConnection()) {
            update(con, "INSERT INTO starexec.job_assoc(job_id, space_id) VALUES (?, ?)", job, space);
        }
    }

    private static int insert(Connection con, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static void update(Connection con, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
