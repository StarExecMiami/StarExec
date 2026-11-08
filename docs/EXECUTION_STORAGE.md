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
PostgreSQL. One partial unique index permits only one unreleased build lease per
resource. Acquisition checks the exact current artifact, attempt, token, source,
staging path and fully booked allowance. The entire observed filesystem must fit
that allowance. Existing build locks precede nonwaiting account/resource locks;
contention refuses the operation without a partial engine binding. Standalone
transactions restore connection state, and refused admission rolls back a newly
inserted lease. A connection close or controller restart does not release it.

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
