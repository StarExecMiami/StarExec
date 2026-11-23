package org.starexec.backend;

import java.util.Objects;

/**
 * Immutable CPU allocation and topology attestation record captured for a single job-pair attempt.
 */
public final class CpuAttestationRecord {

    public enum Status {
        ATTESTED,
        VIOLATED,
        UNQUALIFIED,
        UNAVAILABLE
    }

    private final int pairId;
    private final int attemptNo;
    private final String backendType;
    private final String nodeName;
    private final String isolationMode;
    private final String leasedCpus;
    private final String initialEffectiveCpuset;
    private final String terminalEffectiveCpuset;
    private final String observedProcessCpuset;
    private final String topologySummary;
    private final Status attestationStatus;
    private final String violationReason;

    public CpuAttestationRecord(
            int pairId,
            int attemptNo,
            String backendType,
            String nodeName,
            String isolationMode,
            String leasedCpus,
            String initialEffectiveCpuset,
            String terminalEffectiveCpuset,
            String observedProcessCpuset,
            String topologySummary,
            Status attestationStatus,
            String violationReason) {
        if (pairId <= 0) {
            throw new IllegalArgumentException("pairId must be positive: " + pairId);
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1: " + attemptNo);
        }
        if (backendType == null || backendType.trim().isEmpty()) {
            throw new IllegalArgumentException("backendType must not be blank");
        }
        if (isolationMode == null || isolationMode.trim().isEmpty()) {
            throw new IllegalArgumentException("isolationMode must not be blank");
        }
        this.pairId = pairId;
        this.attemptNo = attemptNo;
        this.backendType = backendType.trim();
        this.nodeName = trimToNull(nodeName);
        this.isolationMode = isolationMode.trim();
        this.leasedCpus = trimToNull(leasedCpus);
        this.initialEffectiveCpuset = trimToNull(initialEffectiveCpuset);
        this.terminalEffectiveCpuset = trimToNull(terminalEffectiveCpuset);
        this.observedProcessCpuset = trimToNull(observedProcessCpuset);
        this.topologySummary = trimToNull(topologySummary);
        this.attestationStatus = Objects.requireNonNull(attestationStatus, "attestationStatus");
        this.violationReason = trimToNull(violationReason);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public int getPairId() {
        return pairId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public String getBackendType() {
        return backendType;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getIsolationMode() {
        return isolationMode;
    }

    public String getLeasedCpus() {
        return leasedCpus;
    }

    public String getInitialEffectiveCpuset() {
        return initialEffectiveCpuset;
    }

    public String getTerminalEffectiveCpuset() {
        return terminalEffectiveCpuset;
    }

    public String getObservedProcessCpuset() {
        return observedProcessCpuset;
    }

    public String getTopologySummary() {
        return topologySummary;
    }

    public Status getAttestationStatus() {
        return attestationStatus;
    }

    public String getViolationReason() {
        return violationReason;
    }
}
