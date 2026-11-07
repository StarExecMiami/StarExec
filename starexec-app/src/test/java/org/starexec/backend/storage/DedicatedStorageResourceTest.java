package org.starexec.backend.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DedicatedStorageResourceTest {
    @TempDir Path files;
    private static final Path ROOT = Path.of("/storage/slot");
    private static final DedicatedStorageResource.Limits LIMITS = new DedicatedStorageResource.Limits(65536, 32);
    private static final String MOUNT = "42 1 7:1 / /storage/slot rw,nodev,nosuid master:1 - ext4 /dev/loop1 rw\n";

    private Path directory(String name) throws IOException {
        return Files.createDirectory(files.resolve(name),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @Test void exactCapacityUsesTheFundamentalBlockUnitAndTotalInodes() throws Exception {
        var capacity = DedicatedStorageResource.capacity("4096:16:32:AB12\n", LIMITS);
        assertEquals(65536, capacity.bytes());
        assertEquals(32, capacity.inodes());
        assertEquals("ab12", capacity.filesystemId());
    }

    @ParameterizedTest @ValueSource(strings = {
            "4096:17:32:ab12", "4096:16:33:ab12", "4096:16:0:ab12", "0:16:32:ab12",
            "4096:0:32:ab12", "4096:-1:32:ab12", "4096:16:-1:ab12", "-1:16:32:ab12",
            "4096:16:32:0", "4096:16:32:0000", "4096:16:32:unknown", "4096:16:32:",
            "4096:16:32", "4096:16:32:ab12:extra", "4096:16:32:ab12\n4096:16:32:ab12",
            "4096:9223372036854775807:32:ab12", "9223372036854775808:1:32:ab12",
            "4096:16:18446744073709551615:ab12", "+4096:16:32:ab12", "4096:16.0:32:ab12"
    })
    void unknownExcessiveOrOverflowingCapacityCannotAuthorizeStorage(String observed) {
        assertThrows(IOException.class, () -> DedicatedStorageResource.capacity(observed, LIMITS));
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void byteAndInodeAllowancesMustBothBePositive(long invalid) {
        assertThrows(IllegalArgumentException.class, () -> new DedicatedStorageResource.Limits(invalid, 1));
        assertThrows(IllegalArgumentException.class, () -> new DedicatedStorageResource.Limits(1, invalid));
    }

    @Test void dedicatedExt4MountRetainsItsIncarnationAndDevice() throws Exception {
        var mount = DedicatedStorageResource.dedicatedMount(DedicatedStorageResource.parseMounts(MOUNT), ROOT);
        assertEquals(42, mount.id());
        assertEquals("7:1", mount.device());
        assertEquals(Path.of("/"), mount.root());
        assertEquals(ROOT, mount.point());
    }

    @ParameterizedTest @ValueSource(strings = {"tmpfs", "overlay", "btrfs", "xfs", "nfs", "fuse.ext4"})
    void anUnimplementedCapacityProviderCannotUseExt4Assumptions(String type) throws Exception {
        var mounts = DedicatedStorageResource.parseMounts(MOUNT.replace("- ext4 ", "- " + type + " "));
        assertThrows(IOException.class, () -> DedicatedStorageResource.dedicatedMount(mounts, ROOT));
    }

    @ParameterizedTest @ValueSource(strings = {"rw", "rw,nodev", "rw,nosuid", "ro,nodev,nosuid", "rw,ro,nodev,nosuid"})
    void requiredMountProtectionsCannotBeOmitted(String options) throws Exception {
        var mounts = DedicatedStorageResource.parseMounts(MOUNT.replace("rw,nodev,nosuid", options));
        assertThrows(IOException.class, () -> DedicatedStorageResource.dedicatedMount(mounts, ROOT));
    }

    @Test void aDirectoryOrBindSubtreeIsNotAnIndependentFilesystem() throws Exception {
        var mounts = DedicatedStorageResource.parseMounts(MOUNT);
        assertThrows(IOException.class, () -> DedicatedStorageResource.dedicatedMount(mounts, ROOT.resolve("child")));
        var subtree = DedicatedStorageResource.parseMounts(MOUNT.replace("7:1 / ", "7:1 /shared/subtree "));
        assertThrows(IOException.class, () -> DedicatedStorageResource.dedicatedMount(subtree, ROOT));
    }

    @ParameterizedTest @ValueSource(strings = {
            "43 1 7:1 / /storage/alias rw,nodev,nosuid - ext4 /dev/loop1 rw\n",
            "43 1 7:2 / /storage/slot rw,nodev,nosuid - ext4 /dev/loop2 rw\n",
            "43 42 0:12 / /storage/slot/nested rw,nodev,nosuid - tmpfs tmpfs rw\n"
    })
    void aliasesStackedMountsAndNestedMountsAreAmbiguous(String extra) throws Exception {
        var mounts = DedicatedStorageResource.parseMounts(MOUNT + extra);
        assertThrows(IOException.class, () -> DedicatedStorageResource.dedicatedMount(mounts, ROOT));
    }

    @Test void unrelatedFilesystemMountsDoNotChangeTheSlot() throws Exception {
        var mounts = DedicatedStorageResource.parseMounts(MOUNT
                + "43 1 7:2 / /storage/other rw,nodev,nosuid - ext4 /dev/loop2 rw\n");
        assertEquals(42, DedicatedStorageResource.dedicatedMount(mounts, ROOT).id());
    }

    @Test void kernelNamespaceRootsAreOpaqueOnUnrelatedPseudoFilesystems() throws Exception {
        var mounts = DedicatedStorageResource.parseMounts(MOUNT
                + "43 1 0:4 net:[123] /run/netns/worker rw - nsfs nsfs rw\n");
        assertEquals(42, DedicatedStorageResource.dedicatedMount(mounts, ROOT).id());
        assertEquals(Path.of("net:[123]"), mounts.get(1).root());
    }

    @Test void kernelEscapesAndOptionalFieldsAreParsedWithoutSplittingNames() throws Exception {
        String escaped = "42 1 7:1 / /storage/slot\\040a\\134b\\011c\\012d rw,nodev,nosuid shared:5 master:1 - ext4 /dev/loop1 rw\n";
        var mount = DedicatedStorageResource.parseMounts(escaped).get(0);
        assertEquals(Path.of("/storage/slot a\\b\tc\nd"), mount.point());
        assertEquals("ext4", mount.type());
    }

    @ParameterizedTest @ValueSource(strings = {
            "not a mount", "0 1 7:1 / /storage/slot rw,nodev,nosuid - ext4 /dev/loop1 rw",
            "42 1 wrong / /storage/slot rw,nodev,nosuid - ext4 /dev/loop1 rw",
            "42 1 7:1 relative /storage/slot rw,nodev,nosuid - ext4 /dev/loop1 rw",
            "42 1 7:1 / /storage/../slot rw,nodev,nosuid - ext4 /dev/loop1 rw",
            "42 1 7:1 / /storage/slot\\041 rw,nodev,nosuid - ext4 /dev/loop1 rw",
            "42 1 7:1 / /storage/slot\\ rw,nodev,nosuid - ext4 /dev/loop1 rw",
            "42 1 7:1 / /storage/slot rw,nodev,nosuid - ext4 /dev/loop1",
            "42 1 7:1 / /storage/slot rw,nodev,nosuid - ext4 /dev/loop1 rw extra"
    })
    void malformedMountIdentityIsRefused(String observed) {
        assertThrows(IOException.class, () -> DedicatedStorageResource.parseMounts(observed));
    }

    @Test void ordinaryPrivateDirectoryCannotPretendToHaveItsOwnCapacity() throws Exception {
        Path slot = directory("slot");
        Path application = directory("application");
        IOException refused = assertThrows(IOException.class,
                () -> DedicatedStorageResource.inspect(slot, LIMITS, List.of(application)));
        assertTrue(refused.getMessage().contains("dedicated mount"));
        try (var contents = Files.list(slot)) { assertEquals(0, contents.count()); }
    }

    @Test void applicationAncestorsAndDescendantsCannotBecomeWritableSlots() throws Exception {
        Path slot = directory("slot");
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(slot, LIMITS, List.of(slot)));
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(slot, LIMITS, List.of(files)));
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(slot, LIMITS, List.of(slot.resolve("child"))));
    }

    @Test void symbolicRootAndAncestorAreRefusedBeforeAnyAllocation() throws Exception {
        Path slot = directory("slot");
        Path application = directory("application");
        Path alias = Files.createSymbolicLink(files.resolve("alias"), slot);
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(alias, LIMITS, List.of(application)));
        Path child = Files.createDirectory(slot.resolve("child"));
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(alias.resolve(child.getFileName()), LIMITS, List.of(application)));
    }

    @Test void inspectionRequiresExplicitProtectedRootsAndPrivatePermissions() throws Exception {
        Path slot = directory("slot");
        Path application = directory("application");
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(slot, LIMITS, List.of()));
        assertThrows(IOException.class, () -> DedicatedStorageResource.inspect(slot, LIMITS, null));
        Files.setPosixFilePermissions(slot, PosixFilePermissions.fromString("rwxr-xr-x"));
        IOException refused = assertThrows(IOException.class,
                () -> DedicatedStorageResource.inspect(slot, LIMITS, List.of(application)));
        assertTrue(refused.getMessage().contains("private identifiable root"));
    }

    @Test void installedStatAgreesWithTheJdkDataBlockCapacity() throws Exception {
        var observation = DedicatedStorageResource.capacity(DedicatedStorageResource.stat(files),
                new DedicatedStorageResource.Limits(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Files.getFileStore(files).getTotalSpace(), observation.bytes());
        assertTrue(observation.inodes() > 0);
    }
}
