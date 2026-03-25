package org.starexec.data.security;

import org.starexec.data.database.UploadSessions;
import org.starexec.data.to.UploadSession;

/**
 * Security checks for resumable upload sessions.
 */
public class UploadSessionSecurity {
    private UploadSessionSecurity() {}

    public static boolean canUserSeeUploadSession(long sessionId, int userId) {
        UploadSession session = UploadSessions.getSession(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        return session.getUserId() == userId || GeneralSecurity.hasAdminReadPrivileges(userId);
    }

    public static boolean canUserManageUploadSession(long sessionId, int userId) {
        UploadSession session = UploadSessions.getSession(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        return session.getUserId() == userId || GeneralSecurity.hasAdminWritePrivileges(userId);
    }
}
