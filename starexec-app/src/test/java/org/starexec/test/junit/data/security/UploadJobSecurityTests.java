package org.starexec.test.junit.data.security;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.UploadJobQueue;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.UploadJobSecurity;
import org.starexec.data.to.UploadJob;

import java.util.Optional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadJobSecurityTests {

    @Test
    public void canUserSeeUploadJobAllowsOwner() {
        UploadJob job = new UploadJob();
        job.setUserId(7);
        try (MockedStatic<UploadJobQueue> uploadJobQueueMock = Mockito.mockStatic(UploadJobQueue.class)) {
            uploadJobQueueMock.when(() -> UploadJobQueue.getJob(14L)).thenReturn(Optional.of(job));
            assertTrue(UploadJobSecurity.canUserSeeUploadJob(14L, 7));
        }
    }

    @Test
    public void canUserManageUploadJobAllowsAdminWrite() {
        UploadJob job = new UploadJob();
        job.setUserId(11);
        try (MockedStatic<UploadJobQueue> uploadJobQueueMock = Mockito.mockStatic(UploadJobQueue.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            uploadJobQueueMock.when(() -> UploadJobQueue.getJob(14L)).thenReturn(Optional.of(job));
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(7)).thenReturn(true);
            assertTrue(UploadJobSecurity.canUserManageUploadJob(14L, 7));
        }
    }

    @Test
    public void canUserManageUploadJobRejectsMissingJob() {
        try (MockedStatic<UploadJobQueue> uploadJobQueueMock = Mockito.mockStatic(UploadJobQueue.class)) {
            uploadJobQueueMock.when(() -> UploadJobQueue.getJob(14L)).thenReturn(Optional.empty());
            assertFalse(UploadJobSecurity.canUserManageUploadJob(14L, 7));
        }
    }
}
