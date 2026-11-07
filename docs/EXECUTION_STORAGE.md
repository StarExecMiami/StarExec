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

The leased admission operation reobserves the filesystem before committing the
lease and existing engine-admission checks together. Resource registration cannot
redirect an existing filesystem key, reuse an existing root under a second key or
change capacity after registration. An old mount observation cannot authorize a
replacement mount. Existing raw engine-observation callbacks remain distinct
from launch admission.

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
mvn -B -pl starexec-app -Dtest=DedicatedStorageResourceTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

These tests check parsing, overflow, aliases, subtrees, nested mounts, protected
roots and refusal of an ordinary directory. They compare the installed statistics
command with Java's capacity observation. Model checks do not demonstrate a real
bounded filesystem under hostile writes.

After applying the migrations to a disposable PostgreSQL database configured
through `STAREXEC_DB_*`, run:

```sh
mvn -B -pl starexec-app -Dtest=SolverBuildStagingCleanupSqlTest,ExecutionStorageLeaseSqlTest,SolverBuildStorageQuotaSqlTest -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.sass=true test
```

The database tests use synthetic provider observations. They verify concurrent
exclusivity, attempt and allowance fencing, refused/failed transaction recovery,
cleanup settlement, residual-file refusal, immutable resource/lease history,
staging reuse, conserved-output integrity and reference-registration races.
They do not establish a real provider capacity limit or deployed acceptance.

Before qualifying a deployment, verify block and inode exhaustion on an owned
provisioned resource without OOM or eviction, persistence after matching stop and
restart, denial of input/foreign-data writes, exclusive concurrent leases and
cleanup failure/retry. Test ordinary solver runs as well as builds on every
supported execution backend. Keep this evidence separate from database and model
tests.

## References

- [Linux ext4 superblock fields](https://docs.kernel.org/filesystems/ext4/super.html)
  describe total blocks and inodes.
- [Linux mount information](https://man7.org/linux/man-pages/man5/proc_pid_mountinfo.5.html)
  describes namespace mount identities, roots and device numbers.
- [ext4 capacity and full-option reporting](https://github.com/torvalds/linux/blob/v7.0/fs/ext4/super.c)
  distinguishes project-quota-filtered statistics from whole-filesystem counts.
- [Podman volume quota requirements](https://docs.podman.io/en/v5.7.0/markdown/podman-volume-create.1.html#quotas)
  distinguish project-quota support from an ordinary local volume.
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/16/transaction-iso.html)
  describes current snapshots and rejection of stale row-lock observations.
- [PostgreSQL function volatility](https://www.postgresql.org/docs/16/xfunc-volatility.html)
  describes snapshot visibility inside constraint and registration functions.
