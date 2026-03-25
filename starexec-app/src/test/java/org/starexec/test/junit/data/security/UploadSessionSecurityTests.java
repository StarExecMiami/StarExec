package org.starexec.test.junit.data.security;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadSessions;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.UploadSessionSecurity;
import org.starexec.data.to.UploadSession;

import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadSessionSecurityTests {

    @Test
    public void canUserSeeUploadSessionReturnsFalseWhenMissing() {
        try (MockedStatic<UploadSessions> uploadSessionsMock = Mockito.mockStatic(UploadSessions.class)) {
            uploadSessionsMock.when(() -> UploadSessions.getSession(11L)).thenReturn(Optional.empty());
            assertFalse(UploadSessionSecurity.canUserSeeUploadSession(11L, 7));
        }
    }

    @Test
    public void canUserSeeUploadSessionAllowsOwner() {
        UploadSession session = new UploadSession();
        session.setUserId(7);
        try (MockedStatic<UploadSessions> uploadSessionsMock = Mockito.mockStatic(UploadSessions.class)) {
            uploadSessionsMock.when(() -> UploadSessions.getSession(11L)).thenReturn(Optional.of(session));
            assertTrue(UploadSessionSecurity.canUserSeeUploadSession(11L, 7));
        }
    }

    @Test
    public void canUserManageUploadSessionAllowsAdminWrite() {
        UploadSession session = new UploadSession();
        session.setUserId(9);
        try (MockedStatic<UploadSessions> uploadSessionsMock = Mockito.mockStatic(UploadSessions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            uploadSessionsMock.when(() -> UploadSessions.getSession(11L)).thenReturn(Optional.of(session));
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(7)).thenReturn(true);
            assertTrue(UploadSessionSecurity.canUserManageUploadSession(11L, 7));
        }
    }

    @Test
    public void canUserManageUploadSessionRejectsNonOwnerWithoutAdminWrite() {
        UploadSession session = new UploadSession();
        session.setUserId(9);
        try (MockedStatic<UploadSessions> uploadSessionsMock = Mockito.mockStatic(UploadSessions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            uploadSessionsMock.when(() -> UploadSessions.getSession(11L)).thenReturn(Optional.of(session));
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(7)).thenReturn(false);
            assertFalse(UploadSessionSecurity.canUserManageUploadSession(11L, 7));
        }
    }
}
