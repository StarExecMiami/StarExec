package org.starexec.test.junit.data.to;

import org.junit.Test;
import org.starexec.data.to.UploadJob;
import org.starexec.data.to.UploadSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UploadSessionTests {

    @Test
    public void getProgressPercentageClampsAtHundred() {
        UploadSession session = new UploadSession();
        session.setTotalBytes(10);
        session.setBytesReceived(25);
        assertEquals(100, session.getProgressPercentage());
    }

    @Test
    public void uploadOpenRecognizesUploadingAndReady() {
        UploadSession session = new UploadSession();
        session.setStatus("UPLOADING");
        assertTrue(session.isUploadOpen());
        session.setStatus("READY");
        assertTrue(session.isUploadOpen());
        session.setStatus("FAILED");
        assertFalse(session.isUploadOpen());
    }

    @Test
    public void terminalStatesAndRetryFlagsAreComputedCorrectly() {
        UploadSession session = new UploadSession();
        session.setStatus("COMPLETE");
        assertTrue(session.isTerminal());

        UploadJob job = new UploadJob();
        job.setStatus("FAILED");
        job.setRetryCount(1);
        job.setMaxRetries(3);
        assertTrue(job.canRetry());

        job.setStatus("COMPLETED");
        assertFalse(job.canRetry());
    }
}
