package org.starexec.test.junit.app;

import com.google.gson.Gson;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.app.RESTServices;
import org.starexec.constants.R;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Spaces;
import org.starexec.data.database.Users;
import org.starexec.data.security.SpaceSecurity;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.Permission;
import org.starexec.data.to.User;
import org.starexec.util.SessionUtil;
import org.starexec.util.Util;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RESTServicesCommunityTests {
	private final RESTServices services = new RESTServices();
	private final Gson gson = new Gson();

	@Test
	public void editCommunityDetailsNormalizesDescAttribute() {
		final int spaceId = 99;
		final int userId = 42;
		final String newDescription = "Updated description";

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter("val")).thenReturn(newDescription);

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<SpaceSecurity> spaceSecurityMock = Mockito.mockStatic(SpaceSecurity.class);
			 MockedStatic<Util> utilMock = Mockito.mockStatic(Util.class);
			 MockedStatic<Spaces> spacesMock = Mockito.mockStatic(Spaces.class)) {

			sessionUtilMock.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			spaceSecurityMock.when(() -> SpaceSecurity.canUpdateSettings(Mockito.anyInt(), Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
					.thenReturn(new ValidatorStatusCode(true));
			utilMock.when(() -> Util.isNullOrEmpty(newDescription)).thenReturn(false);
			spacesMock.when(() -> Spaces.updateDescription(spaceId, newDescription)).thenReturn(true);

			String resultJson = services.editCommunityDetails("desc", spaceId, request);
			ValidatorStatusCode result = gson.fromJson(resultJson, ValidatorStatusCode.class);

			assertTrue("Expected desc attribute to succeed after normalization", result.isSuccess());

			spaceSecurityMock.verify(() -> SpaceSecurity.canUpdateSettings(spaceId, "description", newDescription, userId));
			spacesMock.verify(() -> Spaces.updateDescription(spaceId, newDescription));
		}
	}

	@Test
	public void editCommunityDetailsAllowsEmptyDescription() {
		final int spaceId = 99;
		final int userId = 42;
		final String emptyDescription = "";

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameter("val")).thenReturn(emptyDescription);

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<SpaceSecurity> spaceSecurityMock = Mockito.mockStatic(SpaceSecurity.class);
			 MockedStatic<Util> utilMock = Mockito.mockStatic(Util.class);
			 MockedStatic<Spaces> spacesMock = Mockito.mockStatic(Spaces.class)) {

			sessionUtilMock.when(() -> SessionUtil.getUserId(request)).thenReturn(userId);
			spaceSecurityMock.when(() -> SpaceSecurity.canUpdateSettings(
				spaceId, "description", emptyDescription, userId
			)).thenReturn(new ValidatorStatusCode(true));
			utilMock.when(() -> Util.isNullOrEmpty(emptyDescription)).thenReturn(true);
			spacesMock.when(() -> Spaces.updateDescription(spaceId, emptyDescription)).thenReturn(true);

			String resultJson = services.editCommunityDetails("desc", spaceId, request);
			ValidatorStatusCode result = gson.fromJson(resultJson, ValidatorStatusCode.class);

			assertTrue("Expected an empty description to remain valid", result.isSuccess());
			spacesMock.verify(() -> Spaces.updateDescription(spaceId, emptyDescription));
		}
	}

	@Test
	public void makeLeaderAllowsAdminSelfPromotionAfterDemotion() {
		final int spaceId = 77;
		final int adminId = 7;

		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getParameterValues("selectedIds[]")).thenReturn(new String[]{String.valueOf(adminId)});

		User adminUser = new User();
		adminUser.setId(adminId);
		adminUser.setRole(R.ADMIN_ROLE_NAME);

		Permission perceivedPermission = new Permission();
		perceivedPermission.setLeader(true);

		try (MockedStatic<SessionUtil> sessionUtilMock = Mockito.mockStatic(SessionUtil.class);
			 MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
			 MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class);
			 MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class)) {

			sessionUtilMock.when(() -> SessionUtil.getUserId(request)).thenReturn(adminId);
			sessionUtilMock.when(() -> SessionUtil.removeCachePermission(request, spaceId)).thenAnswer(invocation -> null);

			usersMock.when(() -> Users.get(adminId)).thenReturn(adminUser);
			usersMock.when(() -> Users.setDiskQuota(adminId, R.CL_DEFAULT_DISK_QUOTA)).thenReturn(true);
			usersMock.when(() -> Users.setPairQuota(adminId, R.CL_PAIR_QUOTA)).thenReturn(true);

			generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(adminId)).thenReturn(true);

			permissionsMock.when(() -> Permissions.get(adminId, spaceId)).thenReturn(perceivedPermission);
			permissionsMock.when(() -> Permissions.set(Mockito.eq(adminId), Mockito.eq(spaceId), Mockito.any(Permission.class))).thenReturn(true);
			permissionsMock.when(Permissions::getFullPermission).thenCallRealMethod();

			String response = services.makeLeader(spaceId, request);
			ValidatorStatusCode result = gson.fromJson(response, ValidatorStatusCode.class);

			assertTrue("Expected admin self-promotion to succeed even when cached permissions report leader", result.isSuccess());
		}
	}
}
