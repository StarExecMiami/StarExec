# Bounded execution storage

Untrusted builds and solver runs require private writable storage that survives
physical termination until trusted output validation, publication and cleanup.
The application's shared data tree must not be writable or visible to a runner.
Database byte reservations are necessary accounting, but do not bound writes to a
shared directory. A read-only container root does not protect writable volumes.

## Dedicated filesystem capacity

`DedicatedStorageResource` inspects an operator-provisioned ext4 filesystem.
It never formats, mounts, resizes or configures a filesystem. This provider uses
the filesystem's finite data-block and inode counts, rather than a free-space
estimate or an unsupported directory quota. Other storage providers require
their own enforcement and identity checks before they can be supported.

An eligible resource must meet all of these conditions:

- Its root is a distinct, unambiguous ext4 mount with `rw,nodev,nosuid` and
  owner-only directory permissions. A bind of a shared subtree is refused.
- No other visible mount aliases its device, and no nested mount bypasses its
  capacity. Protected application roots cannot overlap it or share its device.
- Kernel mount information, descriptor-checked directory identity and filesystem
  statistics agree. Linux mount incarnation, device, filesystem ID and root inode
  form the conserved identity together with the root path and complete filesystem
  options; unmount/remount requires explicit recovery.
- Total data blocks multiplied by the fundamental block size fit within the
  execution's booked allowance. The entire inode count also fits its entry
  allowance. Zero, unknown, excessive and overflowing counters are refused.
- Inspection repeats before admission and trusted readback. Capacity or identity
  changes invalidate the observation. The operator must not alter mount topology,
  resize storage or expose another writer while a resource is leased.

The observer uses Linux mount information and `/usr/bin/stat`, with bounded
output, a timeout and a cleared subprocess environment. It does not invoke a
shell, inherit proxy settings or read a credential interface. The filesystem
statistics must also agree with Java's `FileStore` data capacity.

ext4 project quota can clip both counters to a soft limit, so agreement between
`stat` and `FileStore` alone is insufficient. The observer resolves the kernel
device through sysfs and reads its ext4 options from procfs, including defaults
omitted from ordinary mount listings. It refuses positive quota modes, incomplete
observations and unavailable inspection. An operator may not enable quotas while
the filesystem is leased. This provider does not use a project quota as a substitute
for a dedicated bounded filesystem.

## Lease and runtime requirements

Capacity observation alone is not launch permission. A durable exclusive lease
must bind the resource to the exact current attempt and booked allowance before
any untrusted code runs. Rejected or cancelled executions retain ownership until
positive termination, output handling, registered cleanup and accounting settle.
An ambiguous cleanup cannot return a resource to the pool. Multiple executions
must never share one resource, including through a second path or after restart.

`ExecutionStorageLeases` records immutable resource identity and capacity in
PostgreSQL. One shared partial unique index permits only one unreleased claim per
resource across builds and ordinary jobs. Build leases are mirrored with their
original identity and timestamps, including released history; migration does not
book their allowances again. Acquisition checks the exact current artifact, attempt, token, source,
staging path and fully booked allowance. The entire observed filesystem must fit
that allowance. Existing build locks precede nonwaiting account/resource locks;
contention refuses the operation without a partial engine binding. Standalone
transactions restore connection state, and refused admission rolls back a newly
inserted lease. A connection close or controller restart does not release it.

`JobStorageLeases` provides ordinary-job reservations under the same resource
registry. A reservation binds the current pair, attempt, accounting owner and
prior backend execution ID, and books the entire observed resource capacity.
Retrying the same token acknowledges the existing reservation without a second
charge. A different token, stale attempt, changed identity, unavailable quota or
competing build/job claim is refused. Active ownership prevents pair and owner
deletion; released history remains immutable after the pair is removed.

`JobStorageLeases.find` can recover the conserved token by pair and attempt after
a controller restart. `JobExecutionInputs` captures the current owner's permitted
benchmarks, transitive dependencies, solver configurations, processors and
published solver manifests in one repeatable database snapshot. Capture preserves
the reservation and storage-use marker and returns immutable metadata with a
digest of its exact payload bytes. It neither copies inputs nor admits an engine;
subsequent filesystem preparation must revalidate the captured identities and
permissions before use.

`JobExecutionPreparation` copies authorized inputs and trusted controls into the
same reserved resource while preserving their logical mount targets. It commits
the one-way use marker before its first write and conserves a durable receipt
of metadata, content, filesystem identities and mount descriptions. Input copies
are separate from writable execution directories; the receipt and their common
parent are not runner mounts. The caller must conserve the returned fingerprint
before creating an engine. Pre-engine restoration requires that fingerprint,
unchanged permissions and ownership, exact source/copy identities and empty
execution directories. Missing, changed or partial state is retained for trusted
reconciliation rather than recreated or released. `capturePrepared` revalidates
an already-used reservation without reserving or charging it again; the original
`capture` remains restricted to unused reservations.

After creation consumption changes the cancellation locator, `restoreBound`
recovers pre-payload inputs only through that exact request and preparation
fingerprint. It preserves the original lease, metadata, copied bytes and empty
writable directories. Release, stop or cleanup history forbids this restoration.
The original prepared reader remains pre-engine only; neither reader grants
runtime admission or permission to resend creation.

`BuildExecutionRuntime.captureJob` captures the ordinary preparation's read-only
input/control targets and exactly four private writable roots for the existing
kernel verifier. It revalidates inputs before and after capture and preserves the
trusted logical script. Fixed build mount roles and the physical verification
checks remain separate from that ordinary policy. A captured policy still needs
observations from the exact held provider before payload admission.

Runtime collection uses the exact captured mount targets as separate Bash
arguments. Observed path bytes are encoded independently of record delimiters;
the decoder preserves legacy observations while rejecting invalid paths,
duplicate targets and altered physical identity. Build collection retains its
fixed mount roles and the zero-swap check. These command descriptions still
require observation through the exact held provider before admission.

The ordinary private script protocol keeps authorized input and control names
unchanged. Its workspace, cache, monitor output and saved-file staging remain
inside the reserved execution tree; dependency reads use the conserved file
locator. Bootstrap rejects mismatched modes, layouts and stale helpers. Final
workspace/control removal is deferred until trusted positive-stop settlement.
These paths do not implement result promotion or authorize normal dispatch.

`PodmanJobCreation` verifies the owned local rootless peer, immutable image and
registered resource before ordinary preparation. It renders only explicitly
declared provider sources, preserving relative private writes and original input
targets. Read-only inputs cannot alias writable sources or have writable target
ancestors. Planning is serialized so a second caller cannot replace the first
immutable request. The exact request and preparation survive reconnection; restored
history never grants another create. A first acknowledged claim is only input
to a later once-only transport. The adapter itself sends no provider requests
and does not establish effective mounts, payload release or positive termination.

`JobExecutionProtocol` reads and writes typed exact-byte creation and phase
records through owned read-committed transactions. Readback is checked inside the
transaction; only a first acknowledged insertion is new consumption. Historical
reads and replay never authorize another send, and an uncertain commit response
propagates while ownership stays held. Acknowledged commits remain durable when
connection restoration fails, following `Common.runTransactional`. Recorded facts
still require independent verification by the trusted provider observer.

`job_execution_requests` conserves one exact UTF-8 creation
request per ordinary attempt, its immutable image and prepared-input digest,
and the resource incarnation. Consumption requires the current funded used
reservation and commits the pair's cancellation locator in the same transaction.
Only the first acknowledged consumption permits the caller to send once;
an existing request or an uncertain commit response permits observation only.
Request history cannot be changed or deleted after cancellation or pair removal.
The request is bounded by both 16 MiB and the reserved capacity; it is not proof
of effective mounts, engine creation, payload release, termination or cleanup.
The ordinary dispatcher remains separate from this durable protocol foundation.
Creation preserves the preparation's working directory, `/starexec/execution/work`;
the writable execution root is not a substitute for that directory.

`job_execution_receipts` retains immutable creation, payload-release, stop,
output and cleanup histories for that same claim and engine. Release requires
the exact current cancellation locator and a conserved runtime observation;
cleanup intent fences later creation and release. An uncertain consumed create
cannot be treated as never created. Cleanup completion requires the original
operation and observations of an empty resource with the provider absent.
These SQL shape and ordering checks do not attest physical runtime state or
enable used-storage release. Trusted provider, filesystem and output verification
must be coupled before the ordinary dispatcher can use this protocol.
Creation and phase mutations require a read-committed transaction: an older
snapshot could otherwise miss newly consumed creation or cleanup intent.

`OUTPUT_REQUEST` records ordinary output-copy intent separately from completed
`OUTPUT`. Its exact source manifests, normal stop, destination parent identity,
deterministic target and bounded byte/entry allowance are conserved together.
The allocation and its additional quota charge commit atomically, before any
retained-copy write. Nested manifest digests and file/count totals must agree;
malformed, stale, cancelled, unfunded or oversized requests are refused.
Replaying a request cannot book another allowance or authorize another write.
The protected parent is excluded from writable execution resources in both
registration orders. Output reservation and new resource registration serialize
through the existing storage-policy lock, including when their paths are siblings
under that parent. Contention refuses the operation without partial registration
or a quota change.
Copying consumes a separate first confirmed claim against the same current
attempt, funded resource and normal stop. A repeated or uncertain claim never
permits another writer. The freshly created copy directory is bound once before
its contents are written; another incarnation cannot replace that binding.
Claiming and binding leave the complete allowance charged. Neither establishes
retained-byte verification, completed output or permission to retire storage.
The ordinary retention controller verifies the stopped layout and conserved
source before consuming that claim. It creates a new private destination,
binds its incarnation before copying either scope, and freezes/forces the root
only after both immutable subtrees match their original manifests. Recovery
verifies only that exact complete frozen copy. Unknown, unbound or partial
destinations are preserved without adoption, overwrite or an automatic retry.
Both output scopes may be empty; executable build publication retains its
existing regular-file requirement.

`OUTPUT` requires the same copy operation, source/stop manifests, parent and
target paths, and bound directory identity. Its first insertion reconciles only
the unused copy allowance, retaining the measured byte charge; replay cannot
refund it again. Generic source observations cannot establish completed output.
The controller does not apply scientific statistics or retire storage. Stage
accounting, positive provider absence and registered used-resource cleanup remain
separate prerequisites for enabling the normal dispatcher.

`JobOutputReservations` verifies typed allocation readback against the immutable
request. Pending allocations remain charged and prevent owner/pair deletion,
including after execution storage is reclaimed. A reservation is neither a
filesystem-ownership witness nor completed retained output. Unresolved copies
must retain their allocation; completion verification and registered cleanup
must precede charge settlement or ordinary runtime dispatch.

`PodmanJobTransport` sends a preselected curl creation only after the first
acknowledged request consumption. It uses the exact conserved UTF-8 bytes over an
owned local Unix socket, with implicit curl configuration, proxies, redirects and
retries disabled. A bounded concurrent reply reader and transport/parent deadlines
cover oversized responses, disconnection and interruption. The owned child and
reader must terminate before temporary request cleanup. Only HTTP 201 with one
valid container identity returns an acknowledgement; every uncertain reply retains
the consumed request and cannot authorize another POST. The selected Java leg
sends the same exact bytes through a non-repeatable HTTP body on its owned Unix
client. A separate overall deadline covers slow body delivery as well as response
headers; closing the client and joining its worker are required on every outcome.
It does not load Docker credential configuration or switch transports after a
consumed claim. Neither leg starts the container, releases a payload or settles
its storage; the coupled ordinary provider lifecycle remains separate.

The ordinary transport coordinator couples a direct fresh creation response to a
bounded same-socket inspection and the first explicit hold-start claim. Its owned
Java metadata reader preserves SDK raw fields, rejects oversized responses and
uses an overall deadline with positive worker cleanup. Mutating requests retain
the conserved Java or curl selection. Start requires HTTP 204 and never falls
back, retries or sends a payload-release signal after uncertainty. An existing
creation request returns without any new inspection or start. This coordinator
starts only the trusted hold; effective kernel verification, payload release,
positive stop and used-storage cleanup remain separate requirements.

Payload release requires a matching explicit hold-start decision and no prior
release, stop or cleanup. The ordinary coordinator captures the current prepared
inputs and requested limits, executes the existing bounded kernel collector against
the same provider, and uses the shared effective-runtime verifier. It rechecks
inputs and the held process/configuration after collection before conserving one
versioned runtime observation in `RELEASE`. Only its first acknowledged commit
permits one `USR2` request on the selected transport. A cold first decision requires
the same fresh proof; a historical or uncertain release cannot permit another
signal. Provider retirement, output ingestion and used-storage settlement remain
separate requirements before normal dispatch activation.

Stopped ordinary output uses its conserved creation, payload release, exact normal
stop, original configuration and active private-storage ownership. Recovery checks
the original preparation fingerprint, immutable copies and directory identities;
it permits actual contents in the existing writable directories without relaxing
the pre-payload empty-directory guards. Current source files may have changed or
disappeared after execution; the retained copies remain authoritative. Cleanup
intent, cancelled output or replaced layout prevents this recovery. Locating the
directory alone does not ingest files, conserve an output manifest or settle quota.

The stopped-output observer scans the monitor and saved-output scopes with one
aggregate byte/entry budget bounded by the active reservation. Both scope roots
count toward the entry budget; empty optional output is observable. It refuses
output links, rescans exact bytes and revalidates the original layout before
returning a manifest bound to creation and normal stop. This read-only observation
does not persist `OUTPUT`, publish a copy or permit cleanup. Artifact publication
still requires its existing nonempty-file and immutable-copy checks.

`PodmanJobObservation` conserves a created container's configuration only after
matching its never-started identity, trusted launcher, requested environment,
memory/swap/CPU/process limits and exact bind set, including read-only access and
the absence of additional mounts. It stores selected configuration bytes and
their digest through the common ordinary receipt envelope. It does not copy
arbitrary image environment values into history. A cancelled attempt can still
conserve this observation for reconciliation; neither a new nor a historical
`CREATED` observation grants start or payload-release permission. An explicit
`start_requested` claim is distinct: its first acknowledged insertion revalidates
the current job, attempt, owner and already-bound cancellation locator, and
conserves the exact configured identity before one later trusted hold start.
Restored policies, existing observations and uncertain commit replies cannot
authorize that action. This claim sends nothing and cannot release solver code.
Effective kernel
and filesystem verification remain required separately for the held process.

`PodmanJobNonExecution` validates a never-started ordinary container against the
conserved name, image, ownership labels and trusted launcher. It requires the
provider's `created` state, zero process ID, false execution flags, private process
namespace, no restart and unchanged zero start/finish timestamps. The stop receipt
preserves those raw timestamps; its observation time is separate. The SQL protocol
accepts this case only with `never_started` disposition and no consumed payload
release. Normal execution still requires a positively stopped terminal state and
valid completion times. This observation alone neither removes a provider resource,
settles used storage nor authorizes scientific output or a replacement execution.

`PodmanJobStopObservation` separately validates a retained ordinary provider in
`exited` or `dead` state, with zero PID, false execution flags and the same private
identity, launcher and stop policy. It conserves raw start/finish times and exit
code; a finish must be later than the epoch and not before the start. Cancellation
is explicit, and SQL still refuses an unreleased normal result. Reinspection must
match every conserved ownership/stop fact while retaining the original observation
time. This observer performs no removal, output ingestion or used-storage release;
those operations still require the complete runtime and cleanup protocol.

The ordinary-job adapter consumes a one-way storage-use marker before the first
filesystem mutation. That marker grants no engine admission. Only a never-used,
independently reobserved empty resource can be reclaimed by this adapter; beginning
use, an uncertain commit or a changed current attempt retains ownership and its
charge. A settled retry acknowledges history without inspecting or changing the
resource's next owner. Positive process termination, private runtime mounts,
output promotion and cleanup for ordinary jobs still require separate runtime
wiring. These reservation methods do not replace the existing ordinary dispatcher
or establish complete solver-run isolation.

`SolverBuildStaging.prepareLeased` commits resource registration, the exact
positive staging allowance and the exclusive lease together before its first
directory write. It refuses unknown contents and existing unleased directories;
only an already conserved lease may retain its prepared files on retry. Directory,
force or post-commit observation failures retain the reservation. A lost commit
response must not create a second path or book the allowance again. Explicit
transaction helpers keep their caller's commit ownership; standalone operations
continue to refuse foreign transactions. The older path-based preparation methods
remain registry utilities and do not establish a private runtime mount boundary.

The queued dispatcher checks build registration before writing a script or
replacing a log. An already prepared, execution-bound or closed current attempt
cannot fall through to legacy submission. Managed history on an earlier attempt
also bars that fallback: the legacy outcome recorder excludes those pairs.
Unavailable registry reads defer the pair with its captured execution identity,
preserving its output and pending status. Ordinary jobs and unbound legacy builds
retain their existing dispatch path. This read preflight does not grant launch
permission or replace the leased admission, runtime observation and recovery
required by a managed dispatcher.

`ExecutionStorageLeases.findDispatchInputs` captures the exact source and retained
helper only for a current, unbound attempt with a committed active lease. It checks
the helper's original owner, path, booked bytes and retirement state under the
existing build locks. Unknown historical helper identity is refused; a similarly
named live benchmark cannot replace it. Capture neither launches an engine nor
releases storage. A captured snapshot is not launch permission: leased admission
must recheck the current attempt and ownership immediately before enabling execution.
Cancellation, contention and unavailable capacity refuse capture; rollback failures
remain errors. Backend mount construction and runtime attestation remain required.

`BuildExecutionMounts` constructs a private build layout from that leased snapshot.
It observes bounded source bytes, the conserved helper and trusted control files;
it rejects escaping links, hard links, special files, nested source mounts and
read-only inputs that overlap writable storage. Input observation does not change
permissions or relax immutable-output publication checks. Protected host ancestors
and stable mount topology are prerequisites, including between observation and
provider creation.

The source and helper use fixed read-only input paths, and only the three trusted
control files are exposed under the control directory. Work, home, monitor output,
temporary and runtime scratch directories belong to the same leased resource.
Explicit bindings cover `/tmp`, `/run` and `/var/tmp` so Podman's automatic
read-only-root temporary mounts cannot introduce unreserved storage. Unknown or
nonempty staging contents, replaced directories and changed input bytes refuse
admission; failures preserve files and reservations for verified cleanup.

Podman rendering starts with a new host configuration, preserves configured CPU
and memory limits and excludes inherited data binds, volumes, devices and tmpfs.
It requires a positive memory limit, a read-only root, private bind propagation,
dropped capabilities, no privilege escalation and no automatic restart. Shared
host, container and path-based network namespaces are refused. The created
resource still needs independent mount, memory and user-namespace attestation;
serialized flags alone do not establish those properties. Runtime device files
must be distinguished from allocatable storage using their actual file type and
device identity, without exempting unknown writable mounts.

The jobscript recognizes the private layout only for a matching container build.
It validates all fixed paths before loading any control file, selects the mounted
controls and translates the helper and monitor paths before base64 decoding.
The helper must freshly declare the private-path capability; an inherited
environment variable cannot satisfy that check. One exported source replaces the
shared source path. Extra stages, processors, benchmark inputs, pipeline arguments
or benchmark-creating output options are refused rather than silently discarded.

Private builds invoke their own `starexec_build`, bypassing the legacy image-run
shims. Their final shell cleanup leaves read-only controls, output and workspace
for trusted reclamation after positive termination. Ordinary container jobs and
database-connected legacy jobs retain their existing initialization and cleanup.
The host monitor must resolve the registered host output directory independently
of the runner's private path; backend admission and that wiring remain required.

`BuildExecutionHold` supplies a bounded trusted launcher for provider attestation.
It waits before invoking the jobscript, accepts only an explicit `USR2` release,
and refuses to treat `CONT` or `USR1` as authorization. Expiration and termination
signals exit without running the payload. Protected Bash startup prevents inherited
startup files or shell functions from executing before the wait or the jobscript.
Providers must fix the stop signal to `SIGTERM` and disable automatic restarts.

The readiness message only indicates that the signal handlers are installed.
Before release, the controller must verify the exact live provider resource,
effective kernel mounts, user mappings, namespaces and resource limits, then
conserve current-attempt admission. Neither readiness text nor configured flags
can substitute for that observation. An ambiguous start or release response must
retain the same resource for reconciliation; never create or restart a replacement.
The launcher alone does not connect these checks to backend dispatch or recovery.

`BuildExecutionRuntime` captures expected device, filesystem root and inode
coordinates from authorized host inputs and the reserved writable directories.
Its trusted observation command reads only fixed procfs, cgroup and mount paths
inside the exact held resource. The receiver requires a bounded, complete response,
the expected launcher command, an active release handler,
known unprivileged outer UID/GID mappings, separate namespaces, no capabilities,
no privilege escalation and seccomp filtering. The process and observer must share
the resource's mount and cgroup scope.
It returns the process incarnation for independent comparison with provider identity.

Effective memory and process limits must be finite and no greater than the booked
policy. Effective CPU affinity must match both the declared CPU set and the
process's allowed set; quota and period must match their explicit policy. This
checks containment at observation time, not physical-core exclusivity or continued
affinity after release. Process scheduling may change between procfs reads without
changing process identity; a stopped or replaced launcher remains invalid.

All private mounts must retain their expected device, root, inode, file type,
access mode and private propagation. Additional mounts, including unknown read-only
mounts, are refused. The image root must be read-only. Only specific virtual
filesystems and verified standard character devices are allowed; writable device
tmpfs mounts need finite aggregate bounds within the memory limit. Provider-managed
host, hostname and DNS files are optional. When mounted, each requires a matching
node observation, read-only access and a bounded regular file. An absent DNS file
on a network-free container does not exempt any private mount or permit another
mount in its place.

Kernel validation does not establish provider ownership, image identity, source
content integrity, physical storage capacity or current database admission. The
controller must verify those independently and preserve the same owned resource
through observation and release. Do not activate untrusted dispatch using this
receiver alone.

The leased admission operation reobserves the filesystem before committing the
lease and existing engine-admission checks together. Resource registration cannot
redirect an existing filesystem key, reuse an existing root under a second key or
change capacity after registration. An old mount observation cannot authorize a
replacement mount. Existing raw engine-observation callbacks remain distinct
from launch admission.

`ExecutionStorageLeases.claimRelease` adds a durable consumption decision after
trusted held-resource validation. It requires an already bound engine and an
existing exclusive lease; it cannot create either. Existing job, current-attempt,
solver, owner, allowance, resource and cancellation checks are repeated under their
established locks. Capacity is reobserved before and after the claim transaction.

One append-only decision per artifact conserves the execution/request token,
resource and engine identities, exact versioned runtime-observation bytes, their
digest and the request time. Receipt shape is bounded to 8 KiB and validated, but
the receipt itself cannot establish kernel or transport authenticity. The trusted
adapter must obtain it from actual validation of that exact held resource.

Only a new committed insert returns permission. Both a repeated request token and
a new token for the same artifact are refused. Historical readback, connection
close and controller restart never grant another signal. A failed or ambiguous
commit gives no permission, even if later readback discovers the committed record.
Reconcile and positively stop the retained resource instead of retrying release.
If commit is confirmed but connection restoration fails, the existing transaction
helper discards that connection and preserves its known committed result.

Cancellation that wins before the decision refuses it. Later cancellation still
requires an owned provider stop; the database and provider signal do not form one
atomic operation. A release decision is neither delivery acknowledgement nor
physical termination proof, and does not permit publication or cleanup by itself.
Backend integration must commit this decision before its one release request and
retain the exact resource after a lost response. These database methods do not
activate dispatch on their own.

### Managed Podman dispatch

`JobManager` prepares a leased build before writing its execution script. It
selects an existing registered resource only after matching its current physical
identity and capacity, and reserves that entire capacity against the owner's
quota. A retry conserves its original lease and allowance; uncertain preparation
cannot select another resource. Unavailable storage defers the build rather than
falling back to shared output directories.

A build-specific preparation deferral parks that job for the current scheduling
pass after its pair is conditionally returned to pending. Other runnable jobs can
continue, including ordinary jobs behind a deferred high-priority build. The next
pass can retry the build. Genuine backend capacity deferral or failure to reset
the pair still ends the queue pass; no execution or storage guard is bypassed.

`PodmanPrivateBuild` requires a local rootless Unix provider with a verified peer
owner matching the unprivileged application identity, an immutable image without
declared volumes, private mounts and a disabled image healthcheck. Unknown user
namespace mappings and remote providers are refused. Both creation transports
preserve the same complete host policy and trusted hold. The fallback transport
uses the configured finite connection/response deadlines, a bounded JSON response
and an exact container identity; it does not use user curl configuration or a
proxy.

Before sending a managed build create, the controller commits an immutable request
with its existing lease, execution locator, image and stable container name. Only
the first acknowledged insertion permits a send. An uncertain commit or historical
request never permits a retry. The Java transport makes creation, exact-container
start and payload-release requests non-repeatable, including requests with empty
bodies, so HTTP recovery cannot silently resend them after HTTP 429/503.
Curl can be selected only before the Java command has attempted execution.

If the response is lost, an exact unique name and ownership lookup may recover
the same resource for normal policy validation and admission. An unavailable,
ambiguous or empty lookup retains the positive execution and its capacity without
starting a replacement. The creation record is intent, not execution admission,
termination proof or a payload-release decision. Admission cannot rebind its
conserved execution locator.

Startup restores pending creation ownership and advances the allocator beyond all
conserved creation IDs, including already bound history. An unavailable catalog
prevents initialization. Periodic observation also reconciles pending ownership;
it skips an active submission frame and never borrows another execution's tracking.
An exact never-started resource can be cancelled only after rechecking its stored
request, effective private policy and registered physical lease. The cancellation
transaction checks the immutable request digest and records the distinct
`creation_unacknowledged` reason; it cannot use the admission-refusal entry point,
rebook storage, override admission or grant a payload release.

An unavailable, absent, conflicting or changed resource remains unresolved with
its capacity held. Operator recovery must reconcile that same consumed request;
even an empty census cannot authorize another create. No resource or receipt is
invented to make progress.

Admission commits the pair's execution locator and artifact engine binding in one
transaction, before starting the hold. Cancellation can therefore find an admitted
execution without waiting for submission to return. The new release decision also
checks the current pair, locator and job cancellation/pause state under the same
locks. An uncertain admission, start or release retains the exact known provider
resource and its capacity without creating or releasing a replacement.

If a start response is lost before payload release, startup and periodic observation
can restore the same admitted execution. The immutable creation request conserves
the original input fingerprint and application control locators. Recovery requires
unchanged source contents, helper ownership, control files, directory identities,
logical bindings and physical identities; it never recreates missing directories.
The catalog must still authorize the same leased attempt, and the actual container
must still be running the trusted hold with the original policy. Only the first
durable release decision permits one signal. Recovery never creates or starts a
container, adopts changed inputs, or repeats an existing release decision. Older
requests without an original input proof remain unresolved.

After a release response is lost, its durable decision remains consumed regardless
of whether delivery succeeded. A restarted controller can ingest a complete outcome
from that same container; it must not send another release. If no attributable
outcome exists, keep the execution unresolved until explicit cancellation or a
verified outcome is available. Explicit cancellation stops that same owned execution
and conserves its positive cancellation receipt before cleanup. It does not assert
that the payload never started or permit another attempt by itself.

Held-resource validation preserves the scheduler's CPU policy: pinned partitions
retain their cpuset-only boundary without an added CFS quota, while explicit quotas
must match. The kernel's effective affinity, bandwidth policy and zero swap limit
are checked independently. A provider's numeric Linux SIGTERM representation is
equivalent to the requested termination signal; unrelated signals are refused.

Completed private output is located through the versioned layout, exact admitted
execution, stop receipt, active lease and conserved release decision. Runner
environment and broad legacy mounts cannot redirect that locator. Unsupported
build layouts remain unresolved. The periodic build recorder applies conserved
managed outcomes and reclaims staging only after immutable output readback.
Positive process termination and independent staging reclamation remain required.

Explicit Podman cancellation conserves its positive stop observation and atomic
output rejection before removing the container. An unavailable or uncertain
cancellation commit retains the container and tracking. Later observations reuse
the exact cancelled receipt; they cannot turn it into a normal completion or
rewrite an already conserved normal receipt. The periodic recorder discards
cancelled staging only through the existing non-completion cleanup contract.

When admission is definitely refused for an owned container still in `created`
state, the controller rechecks its exact policy, zero PID and absent prior start.
The existing positive lease and allowance must match; a prior release, conflicting
engine or receipt, or cleanup claim refuses the operation. One transaction binds
this non-execution outcome and records cancellation/rejection. That binding cannot
authorize launch, and reactivation of the job cannot admit the rejected artifact.

Only an acknowledged cancellation permits removal without force. An unknown
commit retains the provider and capacity; an exact repeat acknowledges the saved
receipt without another binding write or allowance reservation. After confirmed
removal and a safe execution census, recovery advances only the same current
attempt through the bounded automatic retry lifecycle. The rejected artifact and
receipt remain immutable; the new attempt receives a separate planned artifact.
A killed, deleted, terminal or newer attempt is never reset. The existing single
automatic retry limit still applies; no solver result or measurement is invented.
Periodic cancellation cleanup independently settles staging without publication.

Startup also recovers cancelled created resources from conserved catalog history,
including a lost cancellation or removal response after staging was purged. It
rechecks the exact engine identity, receipt and never-started physical state before
no-force removal, then positively verifies absence and the execution census. A
changed or uncertain resource is retained and excluded from completion ingestion.
This readback never reserves storage again. Only matching in-memory execution
ownership can release a held submission slot; repeated cold recovery creates none.
Registered builds without this proof cannot use the ordinary missing-container reset.

Unobservable creation outcomes, expired or changed holds, uncertain release, and
cancellation racing an already conserved ordinary completion receipt still require
compatible recovery handling. Retain their resource ownership and charges; do not substitute
a Kubernetes non-execution receipt or infer settlement from a terminal pair status.

### Immutable publication and Podman consumers

Set `STAREXEC_BUILD_ARTIFACT_DIR` to an existing application-owned directory with
mode `0700`. The controller checks ancestors, symlinks, kernel mount identity and
physical overlap with shared data, job directories and registered execution roots.
It never creates this root, changes its permissions or substitutes shared storage.
The controller and local rootless provider must resolve the same paths; translated
or remote storage mappings require a separately verified provider implementation.

`SolverBuildRecorder` calls `SolverBuildCompletion` before its legacy recorder.
Successful stopped builds publish executables and logs; failed builds conserve logs
and preserve the original source. A terminal pair status alone cannot authorize
either outcome. The conserved stop receipt, release decision, resource lease and
exact current artifact must agree. Publication and staging accounting are separate;
unavailable storage, quota or verification retains the unresolved staging allowance.

When an ordinary stop receipt precedes cancellation, keep that receipt unchanged.
The cancelled pair can conserve diagnostic logs and settle its stopped staging
through a separate cancellation path; it must not publish executables or record a
solver failure. The solver's source and build status remain unchanged. Its owner
can read the conserved logs only while the same current attempt remains cancelled.
The failure-recording API continues to reject cancelled pairs. Both paths recheck
ownership, current attempt and the exact positive stop before committing output.

An explicitly cancelled Podman build without a payload-release decision can settle
diagnostics only when its complete stopped facts match the immutable admitted
creation, resource and image. Require a private terminal process scope, zero PID,
no running, paused, restarting or privileged state, no automatic restart and valid
start/finish observations. Incomplete or coerced facts remain unresolved. Normal
completion ingestion still requires payload release; cancellation cleanup grants
neither execution permission nor a scientific result or automatic retry.

Ordinary Podman jobs obtain published inputs from the catalog for their current
attempt. Repeated stage references share one exact read-only solver bind; build
logs and the parent store are not exposed. The prebuilt image is fixed by its
immutable identity. Before its jobscript starts, a trusted launcher checks the
source inode, kernel device/root and read-only mount, rejects nested mounts and
writable physical aliases, and requires no-new-privileges without CAP_SYS_ADMIN.
A changed catalog, source or creation policy defers execution or retains its exact
container for observation. It cannot silently select an ordinary writable bind.

This protects published solver inputs. Legacy data/output bindings still require
the ordinary-job isolation work; this feature alone does not establish complete
runner isolation or physical storage capacity.

Completed pair metadata is read relative to an open directory through
`DescriptorDirectory.readQuiescentText`. Each file must be regular, have stable
identity and size, and contain valid UTF-8. The byte bound is enforced during the
read as well as before it. Pair status, timing, watcher and attribute files use
the existing 1 MiB result-file limit. Symbolic links in any ancestor or leaf are
refused; replacing a directory after it opens cannot redirect the read.

The monitor keeps Gson and its established field/status semantics. Unsafe links,
excessive bytes and invalid text cause deterministic refusal before result writes.
Missing optional formats remain optional. A present directory in place of a status
file retains the existing unreadable-file retry contract; ordinary filesystem I/O
remains distinct from invalid content.

These reads require complete writer termination, a private leased output and
serialized trusted reclamation. Java's directory-relative operations do not make
the separate regular-file check and open atomic against a concurrently hostile
leaf. Do not read active untrusted output using this API or treat the reader as
a substitute for the backend's isolation and positive-stop guarantees. Exact
provider/host path binding and all per-stage readers must satisfy the same boundary
before enabling the complete untrusted execution profile.

Private Podman requests may declare a complete mapping from the application's
conserved source paths to provider-visible paths. The mapping refuses missing or
extra sources, relative or dot-segment paths, merged sources and overlap between
read-only inputs and writable storage. Writable scratch paths preserve their exact
positions below the leased staging root; read-only inputs may use independent
provider locations. The original application bindings, host output locator and
runner environment remain unchanged. Expected physical identities must still be
captured from those application bindings and verified against the actual held
resource before release. A path declaration alone cannot authorize execution.

Shared per-stage metadata readers also bind listing and reads to open directories,
refuse symbolic-link ancestors and leaves, and enforce byte bounds while reading.
The status reader retains its bounded entry count and Gson validation. Trusted
Local helpers may publish complete files by atomic replacement while the pair
runs; replacement during a read remains retryable I/O. This publication contract
does not authorize concurrent hostile writers. Untrusted output still requires
private storage and positive complete termination before ingestion.

The legacy Podman data-root translator compares path components rather than text
prefixes. An enabled mapping requires normalized absolute source/container/host
paths; dot segments and relative paths are refused. Exact roots and trailing
separators map consistently, while sibling directory names do not acquire the data
mapping. Log directories outside the data root keep their separate bind even if
their names share a prefix.

Disabled translation and undeclared legacy paths retain their existing behavior.
This helper is lexical mapping, not physical mount attestation or discovery of a
private resource's daemon path. A leased private filesystem needs an explicit
verified mapping and the existing kernel identity checks before dispatch.

`SolverBuildStagingCleanup` reclaims staging independently of immutable executable
retention. Its durable claim binds the execution, resource, cleanup token and
original directory identity or positive absence. It requires a matching conserved
Podman or Kubernetes stop and an already conserved outcome. Unapplied, unknown or
unsupported Local outcomes cannot authorize cleanup.

Before unlinking staging, the coordinator reads published executables or failed
logs back against their original exact manifest. Explicit cancelled/non-execution
receipts allow discarded staging without inventing a scientific result. It removes
only the registered child through directory handles, forces its parent and confirms
the resource empty. Staging-charge settlement and lease release then commit
together; source, helper and immutable-output charges remain independent.

The directory's inode and creation identity must remain stable across partial
cleanup and retries. Missing or changed identity, residual files, unavailable
capacity, force failure or failed settlement retain the claim and allowance.
A replaced or reappeared directory is refused rather than adopted. A completed
retry checks only its old registered path absent and does not touch a later lease
or refund staging twice. Full artifact cleanup can subsequently settle the source
and immutable copy without releasing staging again.

Private resource roots cannot become solver, benchmark or immutable-artifact
references. Registration and asset-reference guards coordinate through a shared
policy row; new registration advances its version so a stale repeatable-read
writer cannot miss the new exclusion. Contention refuses the operation for retry.
Existing immutable resource registration is idempotent; registering new resources
uses a current read-committed transaction. Deploy resource configuration through
the normal operator process before admitting work.

Only authorized source, helper, benchmark and control inputs may be mounted, and
they must be read-only. Output and scratch must remain inside the leased capacity;
other writable mounts need explicit bounds. Preserve CPU placement, scientific
outcomes, memory accounting and the established stop-receipt protocol. A marker,
deadline, provider deletion acknowledgement or terminal solver status does not
prove physical termination.

The observer and lifecycle coordinators are validation components. Provider mounts,
dispatch, result recording and cleanup scheduling must be connected before
enabling this lifecycle. Local process execution also requires a verified complete
isolation and termination scope. No existing deployment is automatically migrated
or made safe by adding this observer.

## Verification

For artifact-store policy, published consumer guards and recorder compatibility:

```sh
mvn -B -pl starexec-app -Dtest=BuildArtifactStoreTest,PublishedSolverMountsTest,PodmanBackendTests,SolverBuildRecorderTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

Against a disposable PostgreSQL database, `SolverBuildCompletionSqlTest` exercises
the actual periodic caller, publication accounting and current-attempt consumer
catalog. Run it after both a fresh migration and an upgrade. Its physical resource
observation and provider stop receipts are synthetic; it does not attest deployed
acceptance or provisioned capacity.

For managed preparation, private provider orchestration and runtime validation:

```sh
mvn -B -pl starexec-app -Dtest=JobManagerManagedBuildDispatchTest,PodmanPrivateBuildDispatchTest,PodmanBackendTests,BuildExecutionMountsTest,BuildExecutionRuntimeTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

The provider orchestration tests use simulated transports. The SQL tests separately
exercise admission visibility, cancellation and lost commit replies; neither is a
substitute for execution on a provisioned bounded resource.

For single-send creation, real loopback HTTP responses and durable request claims:

```sh
mvn -B -pl starexec-app -Dtest=PodmanCreateOnceHttpClientTest,SolverBuildCreationSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

The SQL selection requires a disposable migrated PostgreSQL database. These checks
do not replace recovery tests against the actual container engine.

Run the capacity and mount-contract tests without a database:

```sh
mvn -B -pl starexec-app -Dtest=BuildExecutionMountsTest,SolverArtifactTreeTest,DedicatedStorageResourceTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These tests check parsing, overflow, aliases, subtrees, nested mounts, protected
roots and refusal of an ordinary directory. They compare the installed statistics
command with Java's capacity observation. Model checks do not demonstrate a real
bounded filesystem under hostile writes.

The layout tests verify exact read-only mappings, reserved runtime scratch,
unchanged input bytes, authority revocation, permission/identity changes and safe
directory retries. They also preserve strict immutable-output verification.
Database authority and physical capacity are synthetic in these component tests;
they do not verify dispatcher activation or a deployed application flow.

For the private bootstrap and existing jobscript behavior, run:

```sh
mvn -B -pl starexec-app -Dtest=JobScriptPrivateBuildPathsTest,JobScriptBuildAdmissionTest,SolverBuildCopyBackTest,SolverBuildStatusOrderTest,JobscriptDecodePathArraysTest,ExecutionWorkspaceIsolationTest,AdjustForK8sStageIndexTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These checks execute Bash with disposable files, including missing or redirected
paths, unsupported stage inputs, zero benchmark inputs, unchanged legacy behavior
and retained private controls. They do not attest an application deployment.

The trusted launch wait can be checked independently:

```sh
mvn -B -pl starexec-app -Dtest=BuildExecutionHoldTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

Those process tests cover early execution, explicit release, unrelated signals,
expiration, cancellation and inherited startup-file injection. They do not verify
database admission or the physical capacity of a deployed provider resource.

Run the kernel-observation contract separately:

```sh
mvn -B -pl starexec-app -Dtest=BuildExecutionRuntimeTest,BuildExecutionMountsTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

The runtime tests exercise exact mount identity, input aliases, metadata omissions,
changed namespace/user mappings, CPU widening, missing process limits, malformed
responses and attempts to substitute an already executing jobscript for the hold.
They do not launch a deployed application or establish a physical capacity bound.

For pair metadata and descriptor-relative readback:

```sh
mvn -B -pl starexec-app -Dtest=ContainerJobMonitorTests,DescriptorDirectoryTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These tests cover linked files/ancestors, byte limits, strict UTF-8, read-only file
permissions, retained directory handles, missing output, unreadable-status retry
and unchanged parser behavior. They use owned fixtures without a provider writer;
they do not prove stop or writer exclusion in a deployed stack.

After applying the migrations to a disposable PostgreSQL database configured
through `STAREXEC_DB_*`, run:

```sh
mvn -B -pl starexec-app -Dtest=SolverBuildLeasedStagingSqlTest,SolverArtifactPublicationSqlTest,SolverBuildStagingCleanupSqlTest,ExecutionStorageLeaseSqlTest,SolverBuildStorageQuotaSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

The database tests use synthetic provider observations. They verify concurrent
exclusivity, attempt and allowance fencing, refused/failed transaction recovery,
cleanup settlement, residual-file refusal, immutable resource/lease history,
staging reuse, conserved-output integrity and reference-registration races. Leased
preparation checks committed visibility before the first directory write, competing
preparations, failed and ambiguous commits, retained reservations and retry identity.
They do not establish a real provider capacity limit or deployed acceptance.

For the dispatch-input and helper-retirement boundary, run:

```sh
mvn -B -pl starexec-app -Dtest=SolverBuildDispatchInputsSqlTest,ExecutionStorageLeaseSqlTest,SolverBuildHelperCleanupSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

The lease tests also exercise durable release consumption, competing controllers,
revoked admission, malformed runtime receipts, lost commit responses and immutable
decision history. Apply migrations to a disposable database for both fresh and
upgrade checks. Their capacity and provider observations remain synthetic; no
provider signal or deployed application is exercised by those database cases.

For bounded transitive input preparation, run the focused cases against each
disposable fresh and upgrade database:

```sh
mvn -B -pl starexec-app '-Dtest=JobExecutionPreparationSqlTest#transitiveScientificLayoutsRemainCompleteAtMeasuredScale+oversizedScientificGraphIsRefusedBeforeUseWithoutTrimming' -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These cases construct complete 32- and 64-node dependency graphs, with 47 and 95
edges respectively, using chains and shared dependencies. They check exact input
bytes and relative include names, read-only copies, the complete rendered provider
request, recovery through its conserved preparation, collector arguments and Bash
syntax. They measure receipt, request, argument and synthetic kernel-packet sizes;
the kernel packet must fit the collector's existing read limit. A complete
129-node graph exceeds the declared 128-entry bound and must be refused before
storage use, without trimming the graph or altering the original inputs.
PostgreSQL and file operations are real; capacity, provider and kernel facts are
synthetic. This coverage does not establish arbitrary graph scale, native mount
scaffolding, scientific parser/solver correctness or physical runtime isolation.

Before qualifying a deployment, verify block and inode exhaustion on an owned
provisioned resource without OOM or eviction, persistence after matching stop and
restart, denial of input/foreign-data writes, exclusive concurrent leases and
cleanup failure/retry. Test ordinary solver runs as well as builds on every
supported execution backend. Keep this evidence separate from database and model
tests.

## References

- [Podman read-only temporary mounts](https://docs.podman.io/en/v5.7.0/markdown/podman-run.1.html#read-only-tmpfs)
  describe writable runtime scratch defaults that must be overridden or bounded.
- [Linux PID namespaces](https://man7.org/linux/man-pages/man7/pid_namespaces.7.html)
  describe init-process signal handling and termination of its namespace.
- [Linux signal dispositions](https://man7.org/linux/man-pages/man7/signal.7.html)
  describe signal handlers and their reset on execution of another program.
- [Linux ext4 superblock fields](https://docs.kernel.org/filesystems/ext4/super.html)
  describe total blocks and inodes.
- [Linux mount information](https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html)
  describes namespace mount identities, roots and device numbers.
- [Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)
  defines effective CPU sets and memory, CPU and process controller limits.
- [OCI runtime configuration](https://github.com/opencontainers/runtime-spec/blob/main/config.md)
  includes read-only proc subtrees; they must retain the same proc filesystem identity.
- [Podman DNS configuration](https://docs.podman.io/en/v5.7.0/markdown/podman-run.1.html#dns-ipaddr)
  permits disabling the provider-managed DNS file; observation follows actual mounts.
- [ext4 capacity and full-option reporting](https://github.com/torvalds/linux/blob/v7.0/fs/ext4/super.c)
  distinguishes project-quota-filtered statistics from whole-filesystem counts.
- [Podman volume quota requirements](https://docs.podman.io/en/v5.7.0/markdown/podman-volume-create.1.html#quotas)
  distinguish project-quota support from an ordinary local volume.
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/16/transaction-iso.html)
  describes current snapshots and rejection of stale row-lock observations.
- [PostgreSQL function volatility](https://www.postgresql.org/docs/16/xfunc-volatility.html)
  describes snapshot visibility inside constraint and registration functions.
- [Java file existence checks](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/Files.html#exists(java.nio.file.Path,java.nio.file.LinkOption...))
  can return false when existence is unknown; cleanup and preparation must distinguish
  confirmed absence from a failed observation.
- [Java 17 directory-relative operations](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/SecureDirectoryStream.html)
  bind file access to an open directory and support refusing symbolic links.
- [Linux FIFO behavior](https://man7.org/linux/man-pages/man7/fifo.7.html)
  explains why a regular-file check must not be raced by an active hostile writer.
