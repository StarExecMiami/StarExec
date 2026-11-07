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
  form the conserved identity; unmount/remount requires explicit recovery.
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

## Lease and runtime requirements

Capacity observation alone is not launch permission. A durable exclusive lease
must bind the resource to the exact current attempt and booked allowance before
any untrusted code runs. Rejected or cancelled executions retain ownership until
positive termination, output handling, registered cleanup and accounting settle.
An ambiguous cleanup cannot return a resource to the pool. Multiple executions
must never share one resource, including through a second path or after restart.

Only authorized source, helper, benchmark and control inputs may be mounted, and
they must be read-only. Output and scratch must remain inside the leased capacity;
other writable mounts need explicit bounds. Preserve CPU placement, scientific
outcomes, memory accounting and the established stop-receipt protocol. A marker,
deadline, provider deletion acknowledgement or terminal solver status does not
prove physical termination.

The observer is a capacity-validation component. Durable lease integration,
provider mounts, dispatch, result recording and cleanup must be connected before
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
- [Podman volume quota requirements](https://docs.podman.io/en/v5.7.0/markdown/podman-volume-create.1.html#quotas)
  distinguish project-quota support from an ordinary local volume.
