# Kubernetes execution identity

Status: design accepted, implementation staged. Base `aa999b026`.

## The defect

`KubernetesNativeBackend` allocates execution handles from an in-memory counter:

```java
private int nextExecId = 1;          // line 317
int id = nextExecId++;               // generateExecId(), line 1965
```

The counter resets on every application start, while durable `job_pairs.sge_id`
values from previous lifetimes survive. Every piece of execution state is keyed on
that reusable integer, so an operation about one execution can act on another.

Observed in production 2026-09-05 (pair 42):

```
04:17     application restarts (Helm rev 22 -> 23); nextExecId resets to 1
05:20:39  safety gate marks historical execId 2 stopped   (pair 42 sge_id=2, from 2026-08-27)
05:20:48  submit execId=1  starexec-job-1-48751           (attempt 3)
05:55:41  safety gate marks historical execId 1 stopped
05:55:48  submit execId=2  starexec-job-2-48593           (attempt 4, REUSES 2)
05:55:51  "Skipping running callback for killed execId 2"
05:56:01  "Skipping completion callback for killed execId 2"
```

Attempt 4 ran correctly and produced complete artifacts. Its result was never
ingested. This is reuse across application lifetimes, **not** integer overflow:
the allocator's wrap branch is for `Integer.MAX_VALUE` and was never reached.

`onJobComplete` also *removes* the marker and returns `true` (line 3809). The
monitor treats `true` as processed and adds the id to `completedExecIds`, so the
cancellation marker disappears while suppression persists. Waiting cannot repair it.

## Collision surface — 11 execution-keyed collections

| component | keyed on `Integer` |
|---|---|
| `KubernetesNativeBackend` | `execIdToJobName`, `execIdToPairId`, `execIdToOutputDir`, `jobsHoldingSlot`, `killedExecIds`, `unverifiedExecutions`, `ambiguousSubmissions` |
| `KubernetesJobMonitor` | `completedExecIds`, `runningExecIds`, `pendingWarnedAt`, `cleanupPending` |
| `PodPhaseView` | `byExecId`, built from the `starexec.org/exec-id` label |

Plus int-only APIs: `Backend.killPair(int)`, `killPairConfirmed(int)`, and
`JobCompletionCallback.onJobRunning/Complete/Failed(int, String)`.

`PodPhaseView.of()` groups Pods by the `exec-id` **label**, so two Jobs both
labelled `exec-id: "2"` have their observations merged.

Fixing only `killedExecIds` would leave ten other collision paths intact.

## Identity model

Three distinct concepts, currently conflated into one integer.

1. **Logical attempt** — `pair_id` + `job_pair_attempts.current_attempt_no`.
   Durable. Survives restart. Answers "which attempt may receive this result".

2. **Concrete submission** — one create request. Allocated *before* calling the
   Kubernetes API so a lost response is reconcilable rather than duplicated. A
   per-JVM counter or process UUID alone is insufficient; it must be durable.

3. **Kubernetes object binding** — `namespace` + `Job name` + **`Job UID`**.
   The UID distinguishes historical object instances with the same name. It is
   available at creation (`.resource(job).create()` returns it, line 1624) and at
   every monitor callback site (the monitor already holds the `Job`, line 392) —
   but is captured nowhere: `getUid` has zero occurrences in the backend.

None of the three alone is sufficient. A Job UID does not say which logical
attempt may be written. A pair id survives reruns. A Pod UID sits beneath the Job,
and one Job may create replacement Pods without starting a new logical attempt.

## Required invariants

- **A** Stopping execution A must not suppress, update, cancel, release
  accounting for, or delete execution B because they share a numeric handle.
- **B** A delayed callback from A must not update B's pair, attempt, artifacts,
  accounting, or cleanup state.
- **C** Duplicate delivery for the same concrete execution must be safe.
- **D** A current execution must not be acknowledged as processed before the
  required ingestion outcome is established.
- **E** Restart/reconciliation must not treat a historical integer as proof of
  current execution identity.

## Direction

Carry an immutable `ExecutionRef { legacy execId, jobName, jobUid }` through the
callback interface instead of `(int, String)`; the monitor can populate it from
the `Job` it already holds. Re-key suppression, tracking and cleanup state on the
concrete identity rather than the integer. Keep the integer only as a
compatibility handle for existing interfaces and non-Kubernetes backends, and
resolve it against the expected pair/attempt/submission context before any
mutating operation.

Ownership must be enforced at the **mutation** boundary — in the same transaction
or conditional operation as the result write — not by an earlier lookup that can
race with reassignment. Deletion must use a UID precondition, since a
read-by-name followed by an unconditional delete-by-name can remove a
replacement object.

Artifact paths need the same treatment: `/app/data/logs/3/3/pair_42` is scoped to
the pair, not the execution, so a later attempt overwrites an earlier one's bytes.
Ownership cannot rest on timestamps or "the newest file".

## Staging

This is a core refactor of dispatch bookkeeping (277 `execId` references in a
4,512-line file), not a contained patch. Proposed order, each increment
independently reviewable and testable:

1. `ExecutionRef` + capture the Job UID at creation + thread it through the
   callback interface. Deterministic collision regression (baseline suppressed,
   treatment ingested) — closes the observed production failure.
2. Re-key the monitor's four collections and `PodPhaseView` grouping.
3. Ownership enforcement at DB mutation boundaries + execution-scoped artifact
   locations.
4. UID-preconditioned deletion and slot-release ownership.
5. Durable submission identity + restart reconciliation + ambiguous-create
   handling; additive migration if required.

Increment 1 alone fixes the reported failure. Increments 3-5 are what make the
invariants hold generally.

## Out of scope

Invalid TPTP input, nested `eprover`/wrapper exit propagation, the empty first
postprocessor attribute (`starexec-result=`), and creation of a valid positive
benchmark are separate concerns. The identity regression must hold regardless of
whether a synthetic result represents solver success, failure, or cancellation.

Recovery of the stranded attempt 4 is also separate: no special case, and no
direct status repair.
