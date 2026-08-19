package org.starexec.backend.exception;

/**
 * Signals that a submission was <em>not attempted</em> and the pair should stay queued.
 *
 * <p>This is a control-flow signal, not a failure. {@code Backend.submitScript} can
 * otherwise only answer with an execution id or an error code, and {@code JobManager}
 * turns every error into a terminal status — {@code ERROR_SGE_REJECT} for a negative
 * return, {@code ERROR_SUBMIT_FAIL} for a thrown exception. Neither is correct for
 * "capacity changed between the dispatchability check and the reservation": the pair is
 * blameless and the condition resolves itself on the next scheduling pass.
 *
 * <p>Unchecked, deliberately. {@code BackendTransientException} would have been the
 * natural home, but it extends {@code BackendCommunicationException extends Exception} and
 * {@code Backend.submitScript} declares no {@code throws} clause, so using it would mean
 * widening the interface signature across all six backend implementations for a signal
 * only one of them can raise.
 *
 * <p>Because it is unchecked, {@code JobManager}'s generic {@code catch (Exception)} would
 * swallow it into {@code ERROR_SUBMIT_FAIL} — the exact outcome this type exists to
 * prevent. <strong>Catch ordering is therefore the whole of the guarantee</strong>: the
 * handler for this type must precede the generic one, and the regression test asserts that
 * at the {@code JobManager} boundary rather than on the backend that throws it.
 */
public class SubmissionDeferredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SubmissionDeferredException(String message) {
        super(message);
    }
}
