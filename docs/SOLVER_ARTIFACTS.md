# Solver build artifacts

`solver_build_artifacts` records a build's original solver, owner, pair, attempt,
source path and generation. Live foreign keys may become null when an execution
or solver is deleted; historical identities and cleanup paths remain available.

The lifecycle is `PLANNED → STAGED → VALIDATED → PUBLISHED → RETIRED`. Rejected
artifacts retain cleanup records. Only retired or rejected artifacts can be marked
deleted. Reserved paths and validated payload bytes cannot be replaced. The digest
covers the exact UTF-8 manifest text, including whitespace, rather than JSONB output.

`publish_solver_build_artifact` locks the job, pair, current attempt, solver and
artifact in that order. It checks ownership, source, build generation, successful
completion, deletion state and newer build jobs before switching the solver path
and publication state in one database transaction. A retry of the current
publication succeeds without replacing its timestamp.
Missing pair or solver build status cannot authorize publication. The repeatable
function definition preserves this refusal when upgrading an existing catalogue.

Rebuilding resolves the original source independently of the published artifact
path and advances the build generation before the replacement job exists. Failed
solvers keep their existing failure status until the replacement result is recorded.

Database metadata cannot make runner-writable files immutable. Before validation,
the application must copy a completed execution's output into storage the runner
cannot modify, validate types, links, byte limits and content, then publish that
distinct artifact. Catalog transactions do not provide filesystem isolation or
automatically protect the existing path-based build helpers.

`SolverArtifactTree` creates an exclusive destination, enforces byte and entry
limits, copies regular files into distinct inodes and preserves only relative
links that resolve inside the tree. It refuses hard links, special files and
unsafe ancestors. It normalizes permissions, forces copied files and directories
to storage, and reads the result back against its exact manifest. A failed copy
cleans only the destination it created.

All input writers must have stopped and both trees' ancestors must be protected
before calling it. File permissions alone cannot isolate another process with the
same UID. The caller must reserve the artifact path durably before copying and
complete ownership, quota and cleanup coordination before publication.

Container-mode build helpers require an execution-specific
`STAREXEC_BUILD_OUTPUT_DIR`; generated scripts encode it with the other paths.
They stage solver files under `solver/` and logs under `logs/`, and leave helper
benchmark inputs for application cleanup. Missing staging is an explicit failure;
database-connected legacy jobs retain their existing copy and cleanup behavior.

The producer, script template and trusted recorder must be upgraded together.
Persistent helper scripts must also be refreshed through the deployment process;
updating a WAR alone does not guarantee the helper volume changed. Drain old
generated build jobs or refuse them before dispatch. Never restore compatibility
by allowing a container build to write directly into a published solver tree.

`prepare_solver_build_storage` binds one execution token and staging directory to
the current pair attempt, source and generation. Engine adapters bind the resource
identity they created and record its observed termination. These observations are
separate from solver-written status files. A duplicate callback keeps the original
timestamp; deletion preserves enough identity to finish tracking an already
prepared execution.

Runtime callers use `publish_solver_build_output`, which requires that execution
binding, a recorded stop and an immutable storage root before the metadata
transaction can publish. The root contains `solver/` and any saved `logs/`; only
its solver directory becomes the solver's execution path. A missing optional log
does not turn a successful build into a failure. The lower-level metadata function
does not establish stopped writers, validate files or enforce filesystem quotas.

`SolverBuildPublication` coordinates successful, execution-bound output. It
requires a complete pair and a conserved ordinary Podman or Kubernetes stop
receipt, reserves its destination durably, copies into distinct immutable files,
stores the exact verified manifest, and calls the current-owner publication
operation. The caller supplies an auto-commit connection, a private destination
parent excluded from runner writes, and explicit byte and entry limits. It does
not modify uploaded sources or execution staging. Local process snapshots cannot
substitute for a complete build-stop receipt.

A retry verifies validated output against its existing manifest before attempting
metadata publication; it preserves original validation and publication times.
An unvalidated destination left by a failed or interrupted copy is retained with
its catalogue reservation. A retry never overwrites, adopts or deletes that
destination. Trusted cleanup must resolve it separately. The general-purpose tree
copy still removes only its own failed copy; publication uses the variant that
retains already registered paths.

File forcing follows the guarantees of the configured storage provider. Validate
crash recovery on the deployment volume; database and copy tests alone do not
establish network-filesystem or power-loss durability.

This coordinator does not activate dispatch, replace the legacy build recorder,
enforce mount isolation or account filesystem quotas. Those integrations, failed-
build log retrieval, source retention and cleanup remain required before enabling
the complete execution-storage lifecycle.

Source downloads resolve the recorded original directory against the authorized
solver's ID, owner and current path. A matching published catalogue supplies the
source when the separate hint is absent; contradictory hints, changed snapshots
and invalid roots are refused. Legacy built paths and paths already ending in
`_src` remain supported without deriving source from an immutable executable path.

The source ZIP writer streams regular files and preserves confined relative links
as Unix ZIP symlinks, including directory links without following them. It refuses
escaping or absolute links, hard links, special nodes and symbolic ancestors.
Source contents and modes remain unchanged. Other download archive formats and
the existing solver-download authorization checks are unchanged.

Executable links must remain inside the published `solver/` subtree; the complete
artifact root also holds private logs and is not the executable's link boundary.
Promotion and validated replay check every executable link before publication.
Solver binary and source downloads use the same confined ZIP writer, preserving
the normal enclosing directory and the existing flat reupload layout. The reader
also refuses cross-tree links in previously stored executables, independently of
the publication checks. Confined library links remain links rather than expanded
file copies. These checks do not change other archive/export operations.

The applied build outcome records its artifact reference in the same transaction
as the solver status/path update. Log reads use that reference, not the newest
catalogue row, and hold the solver row stable while verifying the exact manifest
payload and regular log bytes. Symbolic/hard links and changed bytes are refused.
An empty log remains distinct from an absent log. Existing owner/admin permissions
and REST error responses remain unchanged, as does the legacy cleanup locator.
Legacy outcomes record their own applied unbound catalogue identity and keep
their previous log location, including after earlier managed attempts. Managed
output never falls back to that file. Existing managed rows have no guessed migration backfill:
trusted outcome replay must verify the artifact before recording its reference.

Solver deletion commits a tombstone before touching files. Existing stage
references, conserved build input/output and overlapping managed paths retain
their bytes and their existing solver charge for registered cleanup. A deleted
flag is not proof of physical termination or permission to purge those trees.
Unreferenced legacy directories are removed only after the committed request;
their captured charge is released after confirmed absence. Failed cleanup keeps
the charge and supports retry without releasing it twice. Recycle purge consumes
the actual committed selection rather than independently enumerating files and
then changing a different set of solver rows. User deletion refuses a cascade
while managed bytes, current applied output or another user's stage references
still require the account, charge or solver data. It also checks the actual parent
directories and other cleanup paths against conserved and other users' solver
paths before committing. Contended build identities produce a retryable refusal
after the normal job-stop sweep, rather than waiting with conflicting lock order.
Job cleanup uses the directory captured before that commit rather than resolving
a different migration-mode location after its database identity is gone.
Ordinary legacy users can still delete their own historical stages. Registered
managed physical purge remains required before retained accounts can be removed.
`SolverBuildCleanup` provides that registered-tree purge. It commits a cleanup
identity before filesystem changes, rechecks stopped execution and live references,
and holds the captured identities through removal and settlement. Current applied
output remains protected until its solver is deleted and its stage references are
gone. Cleanup of the last retained build can include the deleted solver's original
source, provided no other solver, build or benchmark requires those paths.

The caller supplies trusted staging, output and source parents. Staging/output
names must exactly match their execution and artifact reservations. Descriptor-
relative traversal unlinks links themselves and never chmods linked regular files;
unsafe ancestors, changed parents, unsupported directory forcing and traversal
limits preserve the claim for recovery. Each removed root's parent is forced to
storage and absence is checked before the captured existing solver charge is
released. A filesystem or database-commit failure retains the same cleanup token
and charge for retry. Completed cleanup never adopts a reappeared directory.

New stage references take a solver identity lock and refuse input with a pending
cleanup or a completed managed source removal. Existing historical stage snapshots
are preserved. Cancellation and non-execution receipts can authorize cleanup but
never solver publication. Local cleanup remains refused without complete physical
termination proof. Engine identity and exact receipt bytes survive settlement.

This operation settles the existing solver charge and any separately booked build
storage charges. Mounted isolation, provider capacity enforcement and runtime
integration remain required before enabling the complete lifecycle. The
storage provider must support the directory-forcing semantics being relied on;
these component tests do not establish power-loss behavior on a deployed volume.

Build helpers have a separate shared registry because several attempts may read
one dummy benchmark. Planning captures its original ID, owner, path and booked
bytes while the pair and benchmark still exist. Execution preparation rechecks
that snapshot and refuses changed, missing or retiring input. Historical bindings
are never guessed after metadata disappears; drain or explicitly resolve older
unregistered executions before enabling the complete lifecycle.

Ordinary benchmark deletion, metadata removal and account deletion cannot discard
an unsettled managed helper or its charge. Recycle cleanup calls each guarded
deletion before touching its file; it no longer removes protected paths before SQL.
Earlier successful entries can remain deleted if a later selected entry is refused.
Unbound legacy helpers retain their existing recorder/retirement behavior.

`SolverBuildHelperCleanup` requires matching stop evidence for every registered
reader and refuses unrelated or active consumers. A single durable claim covers
the shared helper, including after pair/solver metadata detaches. It unlinks only
the exact registered file through its trusted benchmark parent, forces that parent
and settles its captured charge once. Parent directories and unrelated siblings
are left alone. New consumer admission serializes with the claim and refuses
retiring or removed helper input; it leaves ordinary benchmark insertion unchanged.
Partial failure, failed commit and reappeared files use
the same retry/refusal policy as registered build-tree cleanup. Local process
snapshots cannot authorize it, and helper settlement does not alter receipt kind
or publish scientific output. Full byte reservations and runtime wiring remain
separate requirements.
Orphan sweeps reuse that deletion protocol and retire metadata only after charge
settlement and reference checks. Explicit permanent removal also refuses retained
or unsettled storage. Permission repair skips symbolic links and shared file
inodes, so cleaning a legacy directory does not chmod a conserved link target.

The legacy recorder excludes pairs with any execution-bound attempt. Its SQL
outcome and helper-retirement operations recheck that boundary under the normal
identity locks, including when an execution is prepared after the initial read.
An unbound legacy result rejects its empty catalogue reservation before updating
the solver, preventing a late execution from claiming the same input. Preparation
checks that durable rejection and the helper under a shared row lock; retirement
holds an exclusive helper lock and refuses a helper referenced by another pair.

Only a successful retirement claim returns the helper path after database
deletion and accounting commit. Filesystem removal then uses the existing bounded
deletion utility. A refused claim never removes files or applies execution-bound
outcomes. Soft-deleted helper paths remain available for cleanup after filesystem
failure. This preserves unbound legacy behavior; it does not supply physical-stop
proof for legacy jobs or activate the isolated execution recorder.

For a terminal unsuccessful build, `SolverBuildPublication.recordFailure` copies
only saved logs and retains the uploaded source and solver path. It requires the
same ordinary Podman or Kubernetes stop receipt; cancellation, non-execution,
killed pairs and unknown dispositions cannot decide a scientific failure. Missing
optional logs are conserved as an empty root; an empty log directory remains a
directory in the manifest. Unavailable staging is an error, not missing logs.

Verified failure manifests remain `STAGED` with immutable validation metadata,
never `VALIDATED` executable output. `record_failed_solver_build_output` rechecks
job, pair, attempt, solver owner, source and generation under the publication lock
order, sets only the current solver's failure status, and rejects the artifact in
one transaction. A retry verifies the existing log bytes without replacing their
manifest or timestamps. A failed copy remains registered for trusted cleanup.

`SolverBuildArtifacts` exposes parameterized JDBC operations for dispatch and
engine adapters. Missing records and mismatched ownership return refusal; database
errors propagate for infrastructure retry. It does not allocate directories or
observe processes, and callers must supply an absolute staging directory.

Build storage budgets and user disk quotas are expressed in bytes. The trusted
publisher books its entire bounded copy allowance in `users.disk_size` before
creating output. Reservations retain the artifact, owner, kind and exact path;
the uploaded source charge is separate. Concurrent reservations serialize on the
account row and refuse lock contention. The quota check uses remaining capacity
without overflowing a signed 64-bit counter. Refused reservations roll back the
path and charge together. Retrying the same budget does not charge it again, and
an existing reservation cannot be enlarged or redirected.

Only conserved, verified output bytes reduce a successful copy's unused allowance.
Manifest storage and this reconciliation commit together. A failed or incomplete
copy keeps its entire booked allowance until registered cleanup confirms every
captured path absent. That cleanup releases each booked charge once in the same
transaction as its completion; a failed commit retains the identity and charge
even when files are already gone. The history remains after settlement.

The bounded staging overload books its allowance before directory creation and
retains it until verified cleanup. The registry-only staging overload is retained
for existing preparation callers, but cannot pass launch admission without an
explicit positive staging allowance. Admission checks the exact artifact, pair,
current attempt, token, source, staging path and accounting owner in one transaction.
The allowance must remain unreleased and covered by the owner's recorded usage;
admission does not invent or enlarge a budget. Owner contention refuses rather
than waits with a reversed lock order. A failed admission commit rolls back engine
binding while retaining the already booked allowance.

Raw engine binding remains available for late callbacks and cleanup observations;
it never grants permission to launch. Cancellation and non-execution keep their
existing receipt contracts. Provider writable-volume capacity enforcement and
dispatcher integration remain prerequisites for activation. Byte and entry limits describe
logical content rather than filesystem block, inode or metadata consumption;
database accounting alone cannot enforce a storage provider's physical capacity.

Use a disposable PostgreSQL database configured through the documented
`STAREXEC_DB_*` variables and apply the migrations before running:

```sh
mvn -B -pl starexec-app -Dtest=SolverArtifactPublicationSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
mvn -B -pl starexec-app -Dtest=SolverBuildPublicationSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
mvn -B -pl starexec-app -Dtest=SolverBuildStorageQuotaSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These database tests cover ownership changes, stale jobs and attempts, rollback,
exact manifest bytes, deletion retention and a forced concurrent build insertion.
They do not establish deployed runner isolation.

The file-copy tests run without a database:

```sh
mvn -B -pl starexec-app -Dtest=SolverArtifactTreeTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```
