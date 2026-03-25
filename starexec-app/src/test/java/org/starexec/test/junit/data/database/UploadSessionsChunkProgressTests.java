package org.starexec.test.junit.data.database;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UploadSessionsChunkProgressTests {

    @Test
    public void contiguousPrefixComputationMatchesExpectedNextChunk() {
        int[] present = {0, 1, 2, 4, 5};
        assertEquals(3, computeNextChunkIndexFromPresentSet(present, 6));

        int[] complete = {0, 1, 2, 3};
        assertEquals(4, computeNextChunkIndexFromPresentSet(complete, 4));
    }

    private int computeNextChunkIndexFromPresentSet(int[] present, int totalChunks) {
        boolean[] marker = new boolean[totalChunks];
        for (int idx : present) {
            if (idx >= 0 && idx < totalChunks) {
                marker[idx] = true;
            }
        }
        int next = 0;
        while (next < totalChunks && marker[next]) {
            next++;
        }
        return next;
    }
}
