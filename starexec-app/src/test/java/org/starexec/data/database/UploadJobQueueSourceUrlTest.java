package org.starexec.data.database;

import org.junit.Test;
import org.mockito.Mockito;
import org.starexec.data.to.UploadJob;

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * An upload by URL is enqueued with its address, and the worker reads it back from the claimed
 * row (#139). The column is new in V0119; nothing else writes or reads it.
 */
public class UploadJobQueueSourceUrlTest {

    private static UploadJob.UploadJobRequest request(String sourceUrl) {
        return new UploadJob.UploadJobRequest.Builder()
            .archivePath("/app/data/Benchmarks/7/20261015-17.00.00.000/Problems.tgz")
            .userId(7)
            .spaceId(10)
            .sourceUrl(sourceUrl)
            .build();
    }

    private static void bind(PreparedStatement ps, UploadJob.UploadJobRequest request) throws Exception {
        Method m = UploadJobQueue.class.getDeclaredMethod(
            "bindUploadJobRequest", PreparedStatement.class, UploadJob.UploadJobRequest.class);
        m.setAccessible(true);
        m.invoke(null, ps, request);
    }

    @Test
    public void theAddressIsWrittenWithTheJob() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        bind(ps, request("https://example.org/Problems.tgz"));

        Mockito.verify(ps).setString(13, "https://example.org/Problems.tgz");
    }

    @Test
    public void anUploadOfAFileWritesNoAddress() throws Exception {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);

        bind(ps, request(null));

        Mockito.verify(ps).setString(13, null);
    }

    @Test
    public void theClaimedRowCarriesTheAddress() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("source_url")).thenReturn("https://example.org/Problems.tgz");
        Method map = UploadJobQueue.class.getDeclaredMethod("mapResultSetToJob", ResultSet.class);
        map.setAccessible(true);

        UploadJob job = (UploadJob) map.invoke(null, rs);

        assertEquals("https://example.org/Problems.tgz", job.getSourceUrl());
    }

    @Test
    public void aRowWithoutAnAddressReadsAsNull() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Method map = UploadJobQueue.class.getDeclaredMethod("mapResultSetToJob", ResultSet.class);
        map.setAccessible(true);

        assertNull(((UploadJob) map.invoke(null, rs)).getSourceUrl());
    }
}
