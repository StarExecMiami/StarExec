# Kubernetes execution identity

Status: increment 1 implemented and tested locally. Base `aa999b026`, design `c81e293a7`.

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

Plus int-only APIs: `Backend.killPair(int)`, `killPairConfirmed(int)`, and all four of
`JobCompletionCallback.onJobRunning/Complete/Failed/StuckPending`.

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

## Increment 1, as built

The first two proposed increments turned out to be one. `ExecutionRef` reaching the
callback changes nothing while the checks that run *before* and *at* callback admission
are still keyed on the integer: `killedExecIds.contains(execId)` would have discarded
the same event, and so would the monitor's `completedExecIds` pre-filter, which is
consulted before the backend sees anything. The smallest coherent unit is therefore:

- **`ExecutionRef {execId, jobName, jobUid}`** — immutable, validating, no equality on
  `execId` alone, no constructor that accepts a missing UID.
- **UID captured from the created Job.** `.resource(job).create()` returns the object;
  its `metadata.uid` is recorded in `execIdToJobUid` alongside the name. A response
  without one is routed to the existing ambiguous-submission path rather than tracked.
- **All four callbacks carry `ExecutionRef`**, built by the monitor from the `Job` it
  already holds. A listed Job with no identity is not acted on.
- **Cancellation scoped to the object.** `killedExecIds` is replaced by
  `killedExecutions` (`Set<ExecutionRef>`) plus `legacyKilledExecIds`, a tombstone for
  the one path — `killPairConfirmed` with no local tracking, the path that fired at
  05:20:39 — where no UID exists. **A tombstone suppresses nothing.** What it recorded
  was that nothing carrying that exec-id *label* survived, which is not evidence about a
  Job created afterwards. It is logged when a later execution inherits the number.
- **The monitor's four collections re-keyed** on `ExecutionRef`:
  `completedExecutions`, `runningExecutions`, `pendingWarnedAt`, `cleanupPending`. Each
  belongs to one Kubernetes Job, so each takes the Job's identity.
- **`PodPhaseView` grouped by the owning Job**, read from the pod's controller
  `ownerReference` UID, with the exec-id label kept as metadata. Replacement pods under
  one Job still merge — that is what `preferred` is for. A managed pod naming no
  controller answers for nothing and is warned about once: it still carries the exec-id
  label, which is the identity being retired. The bare-id accessor survives for callers
  holding no Job, but returns `UNKNOWN` when two Jobs carry the id rather than picking one.

Two guards the increment could not correctly omit, both named by the primary invariant:

- **`resolvePairId` checks ownership.** `execIdToPairId` describes whichever execution
  holds the number now, so a superseded Job reading it would write its outcome onto the
  current execution's pair. When the caller does not own the tracking slot the pair is
  read from the Job's own label instead, and not cached.
- **Releasing accounting checks ownership.** A superseded Job's terminal callback would
  otherwise hand back the *current* execution's submission slot while it is still
  running, and unrelated pairs would be scheduled on top of it.

A cross-vendor review built an executable harness over these predicates and reproduced
eight wrong answers. Four were acted on, and a fifth defect was found by auditing what
those four changed:

- `isSuperseded` read the tracked name twice, once directly and once inside `ownsTracking`.
  A concurrent release between the two reads looked like a handover, and the execution's
  own result would have been dropped as superseded. It now answers from one reading.
- Tracking writes are ordered pair and output directory first, identity last, in both the
  submit path and reconciliation. The identity is what grants ownership of the rest, so
  publishing it first left a window where a callback owned the id while still reading the
  previous execution's pair.
- `resolvePairId` re-checks ownership after reading the cached pair, and falls through to
  the Job's own label if the id changed hands during the call.
- The artifact read is fenced on **positive** ownership. Resolving a pair required
  owning the execution id; reading `execIdToOutputDir` required only that nobody *else*
  owned it. Those differ exactly where it matters — absent tracking satisfies the weaker
  test, as does the window between a concurrent submission publishing its output
  directory and publishing the identity that would reveal it — so one execution's
  `status.json` could be read as another's result. `readTerminalStatus`,
  `readRunsolverVerdict`, `readStageNumber`, `resolveStatusPath`, `persistRunSolverStats`
  and `persistAttributes` now take the `ExecutionRef` and read through a single
  ownership-fenced accessor.
- `PodPhaseView` no longer lets a single unattributable pod answer for a UID-bound
  execution. That fallback was a hedge against a cluster stripping ownerReferences; the
  hedge could alias two Jobs, so it is replaced by a one-shot warning that makes the same
  situation diagnosable instead.

The other four are one pre-existing behaviour, unchanged by this increment: when a create
fails ambiguously and a Job of that name is present, `resolveAmbiguousSubmission` adopts
it and records the name with no UID. A callback naming that Job then owns the tracking by
name. Before this change the same callback read the same pair from the same map, so
nothing is worse — but nothing is better either, and it is why increment 3 below is about
ambiguous-create recovery rather than only durable identity.

## What increment 1 does not establish

In-process identity only. Still open, and not weakened by this change:

- durable identity across an application restart, and ambiguous-create recovery;
- ownership at the DB mutation boundary — the write is still not conditional on the
  execution that produced it;
- artifact ownership: `/app/data/logs/3/3/pair_42` remains scoped to the pair;
- UID-preconditioned deletion;
- the six remaining int-keyed backend collections (`execIdToJobName`, `execIdToPairId`,
  `execIdToOutputDir`, `jobsHoldingSlot`, `unverifiedExecutions`, `ambiguousSubmissions`),
  which the ownership guard now gates on the callback path but does not re-key.

## Remaining staging

1. Ownership enforced in the same transaction as the result write; execution-scoped
   artifact locations.
2. UID-preconditioned deletion and slot-release ownership.
3. Durable submission identity + restart reconciliation + ambiguous-create handling;
   additive migration if required.

## Out of scope

Invalid TPTP input, nested `eprover`/wrapper exit propagation, the empty first
postprocessor attribute (`starexec-result=`), and creation of a valid positive
benchmark are separate concerns. The identity regression must hold regardless of
whether a synthetic result represents solver success, failure, or cancellation.

Recovery of the stranded attempt 4 is also separate: no special case, and no
direct status repair.
