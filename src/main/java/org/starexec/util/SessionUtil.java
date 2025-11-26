package org.starexec.util;

import org.starexec.constants.R;
import org.starexec.data.database.Permissions;
import org.starexec.data.database.Spaces;
import org.starexec.data.to.Permission;
import org.starexec.data.to.User;
import org.starexec.logger.StarLogger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;

/**
 * Contains handy methods for accessing data within a user's session
 * @author Tyler Jensen
 */
public class SessionUtil {	
	private static final StarLogger log = StarLogger.getLogger(SessionUtil.class);
	public static final String USER = "user";	// The string we store the user's User object under
	public static final String PERMISSION_CACHE = "perm";	// The string we store the user's permission cache object under
	/**
	 * @param request The request to retrieve the user object from
	 * @return The user object representing the currently logged in user
	 */
	public static User getUser(HttpServletRequest request) {
		final String method = "getUser";
		log.entry(method);
		
		// If they have a valid session, then check for the user object
		User u = null;
		try {
			HttpSession session = request.getSession(false);
			if (session != null) {
				u = (User) session.getAttribute(SessionUtil.USER);
			}
		} catch (Exception e) {
			log.debug(method, "Exception getting user: " + e.getMessage(), e);
		}
		
		// Only log when there's an issue (reduce log spam)
		if (u == null && request.getSession(false) != null) {
			log.debug(method, "User session exists but user not found in session");
		}
		
		return u;
	}	/**
	 * @param request The request to get the user's id from
	 * @return The current user's id
	 */
	public static int getUserId(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return R.PUBLIC_USER_ID;
		}
		return SessionUtil.getUserId(session);
	}
	
	/**
	 * @param session The session to get the user's id from
	 * @return The current user's id
	 */
	private static int getUserId(HttpSession session) {
		if (session.getAttribute(SessionUtil.USER)==null) {
			return R.PUBLIC_USER_ID;
		}
		 return ((User)session.getAttribute(SessionUtil.USER)).getId();
	}
	
	/**
	 * @param request The request to get the user's cache from
	 * @return The current user's permission cache
	 */
	public static HashMap<Integer, Permission> getPermissionCache(HttpServletRequest request) {
		return SessionUtil.getPermissionCache(request.getSession(false));
	}
		
	/**
	 * @param session The session to get the user's cache from
	 * @return The current user's permission cache
	 */
	@SuppressWarnings("unchecked")
	public static HashMap<Integer, Permission> getPermissionCache(HttpSession session) {
		if (session == null) {
			return new HashMap<>();
		}
		if (session.getAttribute(SessionUtil.PERMISSION_CACHE)==null) {
			HashMap<Integer, Permission> newCache = new HashMap<>();
			session.setAttribute(SessionUtil.PERMISSION_CACHE, newCache);
			return newCache;
		}
		return (HashMap<Integer, Permission>)session.getAttribute(SessionUtil.PERMISSION_CACHE);
	}
	
	/**
	 * @param request The request to get the permission from
	 * @param spaceId The space to get the current user's permissions for
	 * @return The permission associated with the given space
	 */
	public static Permission getPermission(HttpServletRequest request, int spaceId) {
		return SessionUtil.getPermission(request.getSession(false), spaceId);
	}
	
	
	/**
	 * @param session The session to get the permission from
	 * @param spaceId The space to get the current user's permissions for
	 * @return The permission associated with the given space
	 */
	private static Permission getPermission(HttpSession session, int spaceId) {
		if (session == null) {
			return new Permission();
		}
		final int userId = SessionUtil.getUserId(session);
		HashMap<Integer, Permission> cache = SessionUtil.getPermissionCache(session);
		if(!cache.containsKey(spaceId)) {
			SessionUtil.cachePermission(session, spaceId);
			cache = SessionUtil.getPermissionCache(session);
		} else {
			log.debug("Cache hit for spaceId="+spaceId);
		}

		Permission p = cache.get(spaceId);
		if (p != null) {
			log.debug("Returning cached permission: "+p);
			return p;
		}

		boolean isPublic = Spaces.isPublicSpace(spaceId);

		if (userId != R.PUBLIC_USER_ID) {
			// For a real (logged in) user, attempt a one-time forced reload before falling back.
			forceReloadPermission(session, spaceId);
			p = cache.get(spaceId); // cache reference unchanged; entry may have been added
			if (p != null) {
				log.debug("Reload succeeded, returning permission: "+p);
				return p;
			}
		}

		if (isPublic) {
			if (userId == R.PUBLIC_USER_ID) {
				log.debug("Public space and public user; returning empty permission");
			} else {
				log.debug("Public space but no specific permission row; returning empty permission");
			}
			return Permissions.getEmptyPermission();
		}

		log.debug("Permission unresolved (private space) returning null");
		return null;
	}

	/**
	 * Looks up the user's permissions on the given space from the database and adds it
	 * to the user's permission cache
	 * @param session The session where the cache is located
	 * @param spaceId The id of the space to cache the permission for
	 */
	private static void cachePermission(HttpSession session, int spaceId) {
		if (session == null) {
			return;
		}
		HashMap<Integer, Permission> cache = SessionUtil.getPermissionCache(session);
		int userId = SessionUtil.getUserId(session);
		if (cache.containsKey(spaceId)) { return; }
		Permission p = Permissions.get(userId, spaceId);
		if (p != null) {
			cache.put(spaceId, p);
		}
	}

	// Force re-query permission ignoring existing cached absence
	private static void forceReloadPermission(HttpSession session, int spaceId) {
		if (session == null) {
			return;
		}
		HashMap<Integer, Permission> cache = SessionUtil.getPermissionCache(session);
		cache.remove(spaceId);
		// log.debug("forceReloadPermission: removed cache entry for spaceId="+spaceId);
		cachePermission(session, spaceId);
	}
	
	/**
	 * Removes a user's permission for a given space from cache; this prevents
	 * the cached permissions and the actual permissions from becoming desynchronized 
	 *  
	 * @param request the session where the cache is located
	 * @param spaceId the id of the space to remove permissions from cache for
	 * @author Todd Elvers
	 */
	public static void removeCachePermission(HttpServletRequest request, int spaceId){
		HashMap<Integer, Permission> cache = SessionUtil.getPermissionCache(request.getSession());
		cache.remove(spaceId);
	}
}
