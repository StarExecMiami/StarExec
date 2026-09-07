package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.Test;
import org.starexec.backend.ExecutionRef;

/** The identity type itself: what it will accept, and what it refuses to call equal. */
public class ExecutionRefTests {

    private static final String UID_A = "aaaa-a";
    private static final String UID_B = "bbbb-b";

    @Test
    public void carriesTheThreeIdentityComponents() {
        ExecutionRef ref = new ExecutionRef(2, "starexec-job-2-48593", UID_B);

        assertEquals(2, ref.execId());
        assertEquals("starexec-job-2-48593", ref.jobName());
        assertEquals(UID_B, ref.jobUid());
    }

    /**
     * The whole point of the type: two executions holding one number are not equal.
     *
     * <p>Anything weaker — equality on the id, or a fallback to it when a UID is missing —
     * puts the collision straight back into every Map and Set keyed on this.
     */
    @Test
    public void sameExecIdWithADifferentJobIsADifferentExecution() {
        ExecutionRef a = new ExecutionRef(2, "starexec-job-2-11111", UID_A);
        ExecutionRef b = new ExecutionRef(2, "starexec-job-2-48593", UID_B);

        assertNotEquals(a, b);
        assertFalse(a.sameJobAs(b));
    }

    @Test
    public void theSameJobIsEqualAndHashesAlike() {
        ExecutionRef one = new ExecutionRef(2, "starexec-job-2-48593", UID_B);
        ExecutionRef other = new ExecutionRef(2, "starexec-job-2-48593", UID_B);

        assertEquals(one, other);
        assertEquals(one.hashCode(), other.hashCode());
        assertTrue(one.sameJobAs(other));
    }

    /** A name is not an identity: {@code generateJobName} repeats every hundred seconds. */
    @Test
    public void sameNameWithADifferentUidIsADifferentExecution() {
        ExecutionRef a = new ExecutionRef(2, "starexec-job-2-48593", UID_A);
        ExecutionRef b = new ExecutionRef(2, "starexec-job-2-48593", UID_B);

        assertNotEquals(a, b);
        assertFalse(a.sameJobAs(b));
    }

    @Test
    public void refusesAMissingUid() {
        for (String uid : new String[] { null, "", "   " }) {
            try {
                new ExecutionRef(2, "starexec-job-2-48593", uid);
                fail("a reference with no UID identifies nothing and must not be built");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("UID"));
            }
        }
    }

    @Test
    public void refusesAMissingJobName() {
        for (String name : new String[] { null, "", "   " }) {
            try {
                new ExecutionRef(2, name, UID_B);
                fail("a reference with no Job name must not be built");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Job name"));
            }
        }
    }

    @Test
    public void readsIdentityOffAJob() {
        Job job = new Job();
        job.setMetadata(
            new ObjectMetaBuilder().withName("starexec-job-2-48593").withUid(UID_B).build()
        );

        ExecutionRef ref = ExecutionRef.fromJob(2, job);

        assertEquals(new ExecutionRef(2, "starexec-job-2-48593", UID_B), ref);
    }

    /** An incomplete object yields no reference at all, rather than a partial one. */
    @Test
    public void reportsNoIdentityForAnIncompleteJob() {
        assertNull(ExecutionRef.fromJob(2, null));
        assertNull(ExecutionRef.fromJob(2, new Job()));

        Job noUid = new Job();
        noUid.setMetadata(new ObjectMetaBuilder().withName("starexec-job-2-48593").build());
        assertNull(ExecutionRef.fromJob(2, noUid));

        Job noName = new Job();
        noName.setMetadata(new ObjectMetaBuilder().withUid(UID_B).build());
        assertNull(ExecutionRef.fromJob(2, noName));
    }

    @Test
    public void describesItselfWithAllThreeComponents() {
        String described = new ExecutionRef(2, "starexec-job-2-48593", UID_B).toString();

        assertTrue(described.contains("2"));
        assertTrue(described.contains("starexec-job-2-48593"));
        assertTrue(described.contains(UID_B));
    }
}
