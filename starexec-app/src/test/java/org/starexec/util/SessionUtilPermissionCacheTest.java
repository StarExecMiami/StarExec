package org.starexec.util;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.Permission;
import org.starexec.data.to.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionUtilPermissionCacheTest {

	private HttpSession newStatefulSession(User user) {
		Map<String, Object> attrs = new HashMap<>();
		attrs.put(SessionUtil.USER, user);
		HttpSession session = Mockito.mock(HttpSession.class);
		Mockito.when(session.getAttribute(Mockito.anyString())).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
		Mockito.doAnswer(inv -> {
			attrs.put(inv.getArgument(0), inv.getArgument(1));
			return null;
		}).when(session).setAttribute(Mockito.anyString(), Mockito.any());
		return session;
	}

	@Test
	public void invalidateUserPermissionsReloadsStaleCachedPermissionsAcrossSessions() {
		final int targetUserId = 101;
		final int spaceId = 202;

		User targetUser = new User();
		targetUser.setId(targetUserId);

		HttpSession targetSession = newStatefulSession(targetUser);
		HttpServletRequest targetRequest = Mockito.mock(HttpServletRequest.class);
		Mockito.when(targetRequest.getSession(false)).thenReturn(targetSession);

		HttpServletRequest leaderRequestWithoutSession = Mockito.mock(HttpServletRequest.class);
		Mockito.when(leaderRequestWithoutSession.getSession(false)).thenReturn(null);

		Permission initialPerm = new Permission(true);
		initialPerm.setLeader(true);
		Permission updatedPerm = new Permission(false);
		updatedPerm.setLeader(false);

		try (MockedStatic<Permissions> permissions = Mockito.mockStatic(Permissions.class);
			 MockedStatic<Spaces> spaces = Mockito.mockStatic(Spaces.class)) {
			spaces.when(() -> Spaces.isPublicSpace(spaceId)).thenReturn(false);
			permissions.when(() -> Permissions.get(targetUserId, spaceId))
					.thenReturn(initialPerm, updatedPerm);

			Permission firstRead = SessionUtil.getPermission(targetRequest, spaceId);
			assertTrue(firstRead.isLeader());

			Permission cachedHit = SessionUtil.getPermission(targetRequest, spaceId);
			assertTrue(cachedHit.isLeader());
			permissions.verify(() -> Permissions.get(targetUserId, spaceId), Mockito.times(1));

			// Simulates a leader/admin updating targetUser's permissions from another session
			assertDoesNotThrow(() -> SessionUtil.removeCachePermission(leaderRequestWithoutSession, spaceId));
			SessionUtil.invalidateUserPermissions(targetUserId);

			Permission refreshedRead = SessionUtil.getPermission(targetRequest, spaceId);
			assertFalse(refreshedRead.isLeader());
			assertFalse(refreshedRead.canAddSolver());
			permissions.verify(() -> Permissions.get(targetUserId, spaceId), Mockito.times(2));
		}
	}
}
