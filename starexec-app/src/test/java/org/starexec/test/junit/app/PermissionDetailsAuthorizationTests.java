package org.starexec.test.junit.app;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.constants.R;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Spaces;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.to.Permission;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code GET /services/permissions/details/{id}/{spaceId}} must refuse guests and callers who may not read the
 * permissions, and must never serialize a {@link User} (e-mail, role, quotas) for anybody.
 */
public class PermissionDetailsAuthorizationTests {
	private static final int SPACE = 55;
	private static final int TARGET = 21;
	private static final int LEADER = 30;
	private static final int MEMBER = 31;
	private static final int ADMIN = 1;
	private static final String SECRET_EMAIL = "victim@example.org";

	private final RESTServices services = new RESTServices();

	private static User user(int id, String role) {
		User u = new User();
		u.setId(id);
		u.setRole(role);
		u.setEmail(SECRET_EMAIL);
		u.setDiskQuota(123456789L);
		return u;
	}

	private static Permission perm(boolean leader) {
		Permission p = new Permission();
		p.setLeader(leader);
		return p;
	}

	/** Runs the endpoint as {@code callerId} with the given visibility, leadership and admin flags. */
	private String call(int callerId, String callerRole, boolean sees, boolean callerLeads, boolean admin) {
		HttpServletRequest request = mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(callerId);
			session.when(() -> SessionUtil.getUser(request)).thenReturn(user(callerId, callerRole));
			general.when(() -> GeneralSecurity.hasAdminReadPrivileges(callerId)).thenReturn(admin);
			permissions.when(() -> Permissions.canUserSeeSpace(SPACE, callerId)).thenReturn(sees);
			permissions.when(() -> Permissions.get(TARGET, SPACE)).thenReturn(perm(false));
			permissions.when(() -> Permissions.get(callerId, SPACE)).thenReturn(perm(callerLeads));
			spaces.when(() -> Spaces.getParentSpace(SPACE)).thenReturn(1);
			spaces.when(() -> Spaces.get(SPACE)).thenThrow(new AssertionError("space must not be loaded"));
			return services.getPermissionDetails(TARGET, SPACE, request);
		}
	}

	private static void assertNoPersonalData(String json) {
		assertFalse(json, json.contains(SECRET_EMAIL));
		assertFalse(json, json.contains("email"));
		assertFalse(json, json.contains("diskQuota"));
		assertFalse(json, json.contains("\"user\":"));
		assertFalse(json, json.contains("\"space\":"));
	}

	@Test
	public void guestIsRefused() {
		String json = call(R.PUBLIC_USER_ID, "user", true, false, false);
		JsonObject o = new Gson().fromJson(json, JsonObject.class);
		assertFalse(o.get("success").getAsBoolean());
		assertFalse(o.has("perm"));
		assertNoPersonalData(json);
	}

	@Test
	public void memberWhoIsNotLeaderCannotReadSomeoneElsesPermissions() {
		String json = call(MEMBER, "user", true, false, false);
		JsonObject o = new Gson().fromJson(json, JsonObject.class);
		assertFalse(o.get("success").getAsBoolean());
		assertFalse(o.has("perm"));
		assertNoPersonalData(json);
	}

	@Test
	public void userWhoCannotSeeTheSpaceIsRefusedEvenWhenLeaderElsewhere() {
		String json = call(LEADER, "user", false, true, false);
		assertFalse(new Gson().fromJson(json, JsonObject.class).get("success").getAsBoolean());
		assertNoPersonalData(json);
	}

	@Test
	public void leaderStillGetsThePermissionsWithoutPersonalData() {
		String json = call(LEADER, "user", true, true, false);
		JsonObject o = new Gson().fromJson(json, JsonObject.class);
		assertTrue(o.has("perm"));
		assertEquals("user", o.getAsJsonObject("requester").get("role").getAsString());
		assertTrue(o.get("isCommunity").getAsBoolean());
		assertNoPersonalData(json);
	}

	@Test
	public void adminGetsThePermissionsAndTheRoleTheTooltipNeeds() {
		String json = call(ADMIN, "admin", false, false, true);
		JsonObject o = new Gson().fromJson(json, JsonObject.class);
		assertTrue(o.has("perm"));
		assertEquals("admin", o.getAsJsonObject("requester").get("role").getAsString());
		assertNoPersonalData(json);
	}

	@Test
	public void aUserMayReadTheirOwnPermissions() {
		HttpServletRequest request = mock(HttpServletRequest.class);
		try (MockedStatic<SessionUtil> session = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<GeneralSecurity> general = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			session.when(() -> SessionUtil.getUserId(request)).thenReturn(TARGET);
			session.when(() -> SessionUtil.getUser(request)).thenReturn(user(TARGET, "user"));
			general.when(() -> GeneralSecurity.hasAdminReadPrivileges(TARGET)).thenReturn(false);
			permissions.when(() -> Permissions.canUserSeeSpace(SPACE, TARGET)).thenReturn(true);
			permissions.when(() -> Permissions.get(TARGET, SPACE)).thenReturn(perm(false));
			spaces.when(() -> Spaces.getParentSpace(SPACE)).thenReturn(7);
			String json = services.getPermissionDetails(TARGET, SPACE, request);
			assertTrue(new Gson().fromJson(json, JsonObject.class).has("perm"));
			assertNoPersonalData(json);
		}
	}
}
