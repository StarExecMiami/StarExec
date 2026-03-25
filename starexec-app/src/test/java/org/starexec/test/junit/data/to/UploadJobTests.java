package org.starexec.test.junit.data.to;

import org.junit.Test;
import org.starexec.data.to.UploadJob;

import static org.junit.Assert.assertEquals;

public class UploadJobTests {

    @Test
    public void getProgressPercentageClampsAtHundredWhenProcessedExceedsFound() {
        UploadJob job = new UploadJob();
        job.setTotalFilesFound(2340);
        job.setTotalFilesProcessed(2390);

        assertEquals(100, job.getProgressPercentage());
    }

    @Test
    public void getProgressPercentageReturnsZeroWhenFoundIsZeroOrNegative() {
        UploadJob job = new UploadJob();
        job.setTotalFilesFound(0);
        job.setTotalFilesProcessed(10);
        assertEquals(0, job.getProgressPercentage());

        job.setTotalFilesFound(-5);
        job.setTotalFilesProcessed(10);
        assertEquals(0, job.getProgressPercentage());
    }
}
