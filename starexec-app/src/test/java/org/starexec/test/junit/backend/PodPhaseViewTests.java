package org.starexec.test.junit.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import org.starexec.backend.PodPhaseView;

/**
 * Tests for the classifier that separates "a pod is executing" from "a pod exists".
 *
 * <p>Fixtures are written from what Kubernetes produces, not from what the parser reads:
 * phase strings and condition types are those in the Kubernetes API reference, and the
 * Unschedulable message is the scheduler's own wording.
 */
public class PodPhaseViewTests {

    private static final String EXEC_ID_LABEL = "starexec.org/exec-id";
    private static final int EXEC_ID = 77;

    private Pod pod(int execId, String phase, String createdAt) {
        return new PodBuilder()
            .withNewMetadata()
            .withName("pod-" + execId)
            .withCreationTimestamp(createdAt)
            .addToLabels(EXEC_ID_LABEL, String.valueOf(execId))
            .endMetadata()
            .withNewStatus()
            .withPhase(phase)
            .endStatus()
            .build();
    }

    @Test
    public void runningPodReportsRunning() {
        PodPhaseView view = PodPhaseView.of(
            List.of(pod(EXEC_ID, "Running", "2026-08-15T12:00:00Z")),
            EXEC_ID_LABEL
        );

        assertTrue(view.isAvailable());
        assertEquals(PodPhaseView.Phase.RUNNING, view.phaseFor(EXEC_ID));
    }

    @Test
    public void pendingPodReportsPending() {
        PodPhaseView view = PodPhaseView.of(
            List.of(pod(EXEC_ID, "Pending", "2026-08-15T12:00:00Z")),
            EXEC_ID_LABEL
        );

        assertEquals(PodPhaseView.Phase.PENDING, view.phaseFor(EXEC_ID));
    }

    @Test
    public void unknownExecIdReportsUnknown() {
        PodPhaseView view = PodPhaseView.of(List.of(), EXEC_ID_LABEL);

        assertEquals(PodPhaseView.Phase.UNKNOWN, view.phaseFor(EXEC_ID));
    }

    /**
     * An empty listing is a fact ("no pods"); a failed listing is not knowing. Only the
     * second may suppress every judgement, so the two must not be the same object.
     */
    @Test
    public void unavailableViewIsDistinctFromAnEmptyOne() {
        assertFalse(PodPhaseView.unavailable().isAvailable());
        assertTrue(PodPhaseView.of(List.of(), EXEC_ID_LABEL).isAvailable());
    }

    @Test
    public void creationTimestampIsParsedFromRfc3339() {
        PodPhaseView view = PodPhaseView.of(
            List.of(pod(EXEC_ID, "Pending", "2026-08-15T12:00:00Z")),
            EXEC_ID_LABEL
        );

        assertEquals(
            Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(),
            view.createdAtMillis(EXEC_ID)
        );
    }

    /**
     * A malformed timestamp must not abort a poll and must not make a pod look older than
     * it is. Unknown reads as "cannot judge", which every caller treats as "do not act".
     */
    @Test
    public void unparseableTimestampIsUnknownRatherThanZero() {
        PodPhaseView view = PodPhaseView.of(
            List.of(pod(EXEC_ID, "Pending", "not-a-timestamp")),
            EXEC_ID_LABEL
        );

        assertEquals(PodPhaseView.UNKNOWN_TIME, view.createdAtMillis(EXEC_ID));
        assertEquals(PodPhaseView.Phase.PENDING, view.phaseFor(EXEC_ID));
    }

    @Test
    public void missingTimestampIsUnknown() {
        PodPhaseView view = PodPhaseView.of(
            List.of(pod(EXEC_ID, "Pending", null)),
            EXEC_ID_LABEL
        );

        assertEquals(PodPhaseView.UNKNOWN_TIME, view.createdAtMillis(EXEC_ID));
    }

    @Test
    public void schedulerReasonIsCarriedThroughForTheOperator() {
        Pod unschedulable = new PodBuilder()
            .withNewMetadata()
            .withName("pod-" + EXEC_ID)
            .withCreationTimestamp("2026-08-15T12:00:00Z")
            .addToLabels(EXEC_ID_LABEL, String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withPhase("Pending")
            .withConditions(
                new PodConditionBuilder()
                    .withType("PodScheduled")
                    .withStatus("False")
                    .withReason("Unschedulable")
                    .withMessage(
                        "0/6 nodes are available: 6 node(s) didn't match Pod's node affinity/selector."
                    )
                    .build()
            )
            .endStatus()
            .build();

        String reason = PodPhaseView
            .of(List.of(unschedulable), EXEC_ID_LABEL)
            .describeWhyPending(EXEC_ID);

        assertTrue(reason.contains("Unschedulable"));
        assertTrue(reason.contains("didn't match Pod's node affinity/selector"));
    }

    /**
     * A pod that cannot pull its image also stays in phase Pending, and with backoffLimit 0
     * and restartPolicy Never the Job never fails, so this is the second way a pair waits
     * forever. The container state is where that reason lives.
     */
    @Test
    public void imagePullFailureIsReportedFromTheContainerState() {
        Pod stuckPulling = new PodBuilder()
            .withNewMetadata()
            .withName("pod-" + EXEC_ID)
            .withCreationTimestamp("2026-08-15T12:00:00Z")
            .addToLabels(EXEC_ID_LABEL, String.valueOf(EXEC_ID))
            .endMetadata()
            .withNewStatus()
            .withPhase("Pending")
            .withContainerStatuses(
                new ContainerStatusBuilder()
                    .withName("job-runner")
                    .withState(
                        new ContainerStateBuilder()
                            .withNewWaiting()
                            .withReason("ImagePullBackOff")
                            .withMessage("Back-off pulling image \"starexec:missing\"")
                            .endWaiting()
                            .build()
                    )
                    .build()
            )
            .endStatus()
            .build();

        String reason = PodPhaseView
            .of(List.of(stuckPulling), EXEC_ID_LABEL)
            .describeWhyPending(EXEC_ID);

        assertTrue(reason.contains("ImagePullBackOff"));
    }

    /** A running pod must win, so a job is never called stuck while one of its pods runs. */
    @Test
    public void aRunningPodWinsOverAPendingOneForTheSameExecId() {
        PodPhaseView view = PodPhaseView.of(
            List.of(
                pod(EXEC_ID, "Pending", "2026-08-15T12:00:00Z"),
                pod(EXEC_ID, "Running", "2026-08-15T11:00:00Z")
            ),
            EXEC_ID_LABEL
        );

        assertEquals(PodPhaseView.Phase.RUNNING, view.phaseFor(EXEC_ID));
    }

    /**
     * Between two pending pods the newer one is the current attempt. Taking the older
     * timestamp would make a threshold fire sooner than the attempt's real age.
     */
    @Test
    public void theNewerPendingPodDatesTheCurrentAttempt() {
        PodPhaseView view = PodPhaseView.of(
            List.of(
                pod(EXEC_ID, "Pending", "2026-08-15T10:00:00Z"),
                pod(EXEC_ID, "Pending", "2026-08-15T12:00:00Z")
            ),
            EXEC_ID_LABEL
        );

        assertEquals(
            Instant.parse("2026-08-15T12:00:00Z").toEpochMilli(),
            view.createdAtMillis(EXEC_ID)
        );
    }

    @Test
    public void podsWithoutAUsableExecIdLabelAreIgnored() {
        Pod unlabelled = new PodBuilder()
            .withNewMetadata()
            .withName("stray")
            .endMetadata()
            .withNewStatus()
            .withPhase("Running")
            .endStatus()
            .build();
        Pod malformed = pod(EXEC_ID, "Running", "2026-08-15T12:00:00Z");
        malformed.getMetadata().getLabels().put(EXEC_ID_LABEL, "not-a-number");

        PodPhaseView view = PodPhaseView.of(List.of(unlabelled, malformed), EXEC_ID_LABEL);

        assertTrue(view.isAvailable());
        assertEquals(PodPhaseView.Phase.UNKNOWN, view.phaseFor(EXEC_ID));
    }

    /** Succeeded and Failed are the Job's business; this view declines to judge them. */
    @Test
    public void terminalPodPhasesAreNotJudged() {
        PodPhaseView view = PodPhaseView.of(
            List.of(
                pod(1, "Succeeded", "2026-08-15T12:00:00Z"),
                pod(2, "Failed", "2026-08-15T12:00:00Z")
            ),
            EXEC_ID_LABEL
        );

        assertEquals(PodPhaseView.Phase.UNKNOWN, view.phaseFor(1));
        assertEquals(PodPhaseView.Phase.UNKNOWN, view.phaseFor(2));
    }
}
