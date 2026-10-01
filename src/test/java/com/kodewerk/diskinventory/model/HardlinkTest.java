package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.MAC, OS.LINUX})
class HardlinkTest {

    private static final int MIB = 10 * 1024 * 1024;

    private final DiskUsageModel model = new DiskUsageModel();

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }

    private static void link(Path from, Path to) throws IOException {
        Files.createDirectories(from.getParent());
        Files.createLink(from, to);
    }

    private static DirectoryNode node(ScanResult r, String rel) {
        DirectoryNode n = r.root();
        for (Path segment : Path.of(rel)) {
            n = n.child(segment.toString()).orElseThrow();
        }
        return n;
    }

    private static FileEntry entry(ScanResult r, String rel) {
        Path p = Path.of(rel);
        DirectoryNode dir = p.getParent() == null ? r.root() : node(r, p.getParent().toString());
        String name = p.getFileName().toString();
        return dir.files().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void hardlinkCountedOnce(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);
        assertEquals(MIB, r.root().totalSize());
        assertEquals(MIB, node(r, "a").totalSize());
        assertEquals(0, node(r, "b").totalSize());
        assertEquals(0, node(r, "b").totalSize(SizeMode.ALLOCATED));
        assertEquals(node(r, "a").totalSize(SizeMode.ALLOCATED), r.root().totalSize(SizeMode.ALLOCATED));
        assertEquals(MIB, entry(r, "b/f").size());     // listed at full size, not charged
    }

    @Test
    void twoLinksInOneDirectoryCountedOnce(@TempDir Path root) throws IOException {
        write(root.resolve("d/x"), MIB);
        link(root.resolve("d/y"), root.resolve("d/x"));
        ScanResult r = model.scan(root);
        assertEquals(MIB, node(r, "d").totalSize());
        assertEquals(MIB, node(r, "d").directFileSize());
        assertEquals(MIB, r.root().totalSize());
    }

    @Test
    void scanParentResettlesOwnership(@TempDir Path parent) throws IOException {
        write(parent.resolve("child/f"), MIB);
        link(parent.resolve("aaa/f"), parent.resolve("child/f"));
        ScanResult known = model.scan(parent.resolve("child"));
        assertEquals(MIB, known.root().totalSize());
        ScanResult up = model.scanParent(known, (d, n, b) -> { });
        assertEquals(MIB, up.root().totalSize());
        assertEquals(MIB, node(up, "aaa").totalSize());
        assertEquals(0, node(up, "child").totalSize());
        assertEquals(MIB, known.root().totalSize());    // previous result untouched
    }

    @Test
    void scanParentWithGrownSharedFileMovesTheOldCharge(@TempDir Path parent) throws IOException {
        write(parent.resolve("child/f"), MIB);
        link(parent.resolve("aaa/f"), parent.resolve("child/f"));
        ScanResult known = model.scan(parent.resolve("child"));
        Files.write(parent.resolve("child/f"), new byte[2 * MIB], StandardOpenOption.APPEND);

        ScanResult up = model.scanParent(known, (d, n, b) -> { });

        assertEquals(0, node(up, "child").totalSize());
        assertEquals(0, node(up, "child").totalSize(SizeMode.ALLOCATED));
        assertEquals(3 * MIB, node(up, "aaa").totalSize());
        assertEquals(3 * MIB, up.root().totalSize());
    }

    private static final DiskUsageModel.ScanListener QUIET = (d, n, b) -> { };

    private static Object key(ScanResult r, String rel) {
        return entry(r, rel).fileKey();
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void ownerIsSmallestPathRegardlessOfWalkOrder(@TempDir Path root) throws IOException {
        write(root.resolve("b/f"), MIB);
        link(root.resolve("a/f"), root.resolve("b/f"));
        ScanResult r = model.scan(root);
        Object k = key(r, "a/f");
        assertEquals(Optional.of(root.resolve("a/f")), r.inodes().owner(k));

        ScanResult ba = model.rescan(model.rescan(r, root.resolve("b"), QUIET), root.resolve("a"), QUIET);
        ScanResult ab = model.rescan(model.rescan(model.scan(root), root.resolve("a"), QUIET), root.resolve("b"), QUIET);

        for (ScanResult x : List.of(ba, ab)) {
            assertEquals(Optional.of(root.resolve("a/f")), x.inodes().owner(k));
            assertEquals(MIB, node(x, "a").totalSize());
            assertEquals(0, node(x, "b").totalSize());
            assertEquals(MIB, x.root().totalSize());
            TestTrees.assertMatchesFreshScan(model, x);
        }
    }

    @Test
    void deleteMovesOwnershipToSurvivingLink(@TempDir Path root) throws IOException {
        write(root.resolve("a/x/big"), MIB);
        link(root.resolve("b/y/z/big"), root.resolve("a/x/big"));
        ScanResult r = model.scan(root);
        Object k = key(r, "a/x/big");

        Files.delete(root.resolve("a/x/big"));
        ScanResult after = model.rescan(r, root.resolve("a/x/big"), QUIET);

        assertEquals(0, node(after, "a/x").totalSize());
        assertEquals(0, node(after, "a/x").totalSize(SizeMode.ALLOCATED));
        assertEquals(MIB, node(after, "b/y/z").totalSize());
        assertEquals(MIB, after.root().totalSize());
        assertEquals(Optional.of(root.resolve("b/y/z/big")), after.inodes().owner(k));
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void deleteLastLinkRemovesBytes(@TempDir Path root) throws IOException {
        write(root.resolve("a/x/f"), MIB);
        write(root.resolve("a/x/g"), 10);
        ScanResult r = model.scan(root);

        Files.delete(root.resolve("a/x/f"));
        ScanResult after = model.rescan(r, root.resolve("a/x/f"), QUIET);

        assertEquals(node(r, "a/x").totalSize() - MIB, node(after, "a/x").totalSize());
        assertEquals(node(r, "a").totalSize() - MIB, node(after, "a").totalSize());
        assertEquals(r.root().totalSize() - MIB, after.root().totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void deleteOneOfTwoLinksInSameDirectory(@TempDir Path root) throws IOException {
        write(root.resolve("d/x"), MIB);
        link(root.resolve("d/y"), root.resolve("d/x"));
        ScanResult r = model.scan(root);

        Files.delete(root.resolve("d/x"));
        ScanResult after = model.rescan(r, root.resolve("d/x"), QUIET);

        assertEquals(MIB, node(after, "d").totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanAddingSmallerLinkMovesOwnership(@TempDir Path root) throws IOException {
        write(root.resolve("m/f"), MIB);
        link(root.resolve("z/f"), root.resolve("m/f"));     // keep nlink > 1 at scan time
        ScanResult r = model.scan(root);
        Object k = key(r, "m/f");

        link(root.resolve("a/f"), root.resolve("m/f"));
        ScanResult after = model.rescan(r, root.resolve("a"), QUIET);

        assertEquals(Optional.of(root.resolve("a/f")), after.inodes().owner(k));
        assertEquals(0, node(after, "m").totalSize());
        assertEquals(MIB, node(after, "a").totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOfVanishedDirectoryResettlesOwnership(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);
        Object k = key(r, "a/f");

        deleteTree(root.resolve("a"));
        ScanResult after = model.rescan(r, root.resolve("a"), QUIET);

        assertEquals(Optional.of(root.resolve("b/f")), after.inodes().owner(k));
        assertEquals(MIB, after.root().totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOfGrownSharedFileUpdatesOwnerCharge(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);

        Files.write(root.resolve("a/f"), new byte[MIB], StandardOpenOption.APPEND);
        ScanResult viaLink = model.rescan(r, root.resolve("b"), QUIET);     // owner a/f unchanged
        assertEquals(2 * MIB, node(viaLink, "a").totalSize());
        TestTrees.assertMatchesFreshScan(model, viaLink);

        Files.write(root.resolve("a/f"), new byte[MIB], StandardOpenOption.APPEND);
        ScanResult viaOwner = model.rescan(viaLink, root.resolve("a/f"), QUIET);
        assertEquals(3 * MIB, node(viaOwner, "a").totalSize());
        TestTrees.assertMatchesFreshScan(model, viaOwner);
    }

    @Test
    void largestFilesListsSharedFileOnce(@TempDir Path root) throws IOException {
        write(root.resolve("a/top"), MIB);
        link(root.resolve("b/top"), root.resolve("a/top"));
        write(root.resolve("c/small"), 100);
        ScanResult r = model.scan(root);

        List<FileRef> top = r.largestFiles(r.root(), 10, SizeMode.LOGICAL);

        assertEquals(2, top.size());
        assertEquals(root.resolve("a/top"), top.getFirst().path());
        assertEquals(List.of(root.resolve("b/top")), top.getFirst().otherLinks());
    }

    @Test
    void largestFilesSkipsSmallSharedFilesButKeepsBigOnesUnderTheirSmallestLink(@TempDir Path root)
            throws IOException {
        for (int i = 0; i < 3; i++) {
            write(root.resolve("u/big" + i), 1000);
        }
        write(root.resolve("z/small"), 10);
        link(root.resolve("y/small"), root.resolve("z/small"));
        write(root.resolve("x2/shared"), 2000);
        link(root.resolve("x1/shared"), root.resolve("x2/shared"));
        ScanResult r = model.scan(root);

        List<FileRef> top = r.largestFiles(r.root(), 3, SizeMode.LOGICAL);

        assertEquals(3, top.size());
        assertEquals(root.resolve("x1/shared"), top.getFirst().path());
        assertEquals(List.of(root.resolve("x2/shared")), top.getFirst().otherLinks());
        assertTrue(top.stream().skip(1).allMatch(f -> f.file().size() == 1000), top.toString());
    }

    @Test
    void deleteAllLinksFreesTheFile(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);

        Freed freed = model.freedOnDisk(r, Set.of(root.resolve("a/f"), root.resolve("b/f")));

        assertEquals(entry(r, "a/f").allocated(), freed.freed());
        assertEquals(0, freed.staying());
    }

    @Test
    void freedOnDiskCountsLinksOutsideRoot(@TempDir Path tmp) throws IOException {
        write(tmp.resolve("root/f"), MIB);
        link(tmp.resolve("outside/f"), tmp.resolve("root/f"));
        ScanResult r = model.scan(tmp.resolve("root"));
        long allocated = entry(r, "f").allocated();

        Freed freed = model.freedOnDisk(r, Set.of(tmp.resolve("root/f")));

        assertEquals(0, freed.freed());
        assertEquals(allocated, freed.staying());

        Files.delete(tmp.resolve("root/f"));
        ScanResult after = model.rescan(r, tmp.resolve("root/f"), QUIET);
        assertEquals(0, after.root().totalSize());
    }

    @Test
    void freedOnDiskUsesFreshNlink(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);
        long allocated = entry(r, "a/f").allocated();

        Files.delete(root.resolve("b/f"));
        Freed freed = model.freedOnDisk(r, Set.of(root.resolve("a/f")));

        assertEquals(allocated, freed.freed());
        assertEquals(0, freed.staying());
    }

    @Test
    void freedOnDiskOfDirectoryCountsUnsharedFilesAndOwnedLinks(@TempDir Path root) throws IOException {
        write(root.resolve("d/plain"), 100);
        write(root.resolve("d/f"), MIB);
        link(root.resolve("e/f"), root.resolve("d/f"));
        ScanResult r = model.scan(root);
        long plainAllocated = entry(r, "d/plain").allocated();
        long fAllocated = entry(r, "d/f").allocated();

        Freed freed = model.freedOnDisk(r, Set.of(root.resolve("d")));

        assertEquals(plainAllocated, freed.freed());
        assertEquals(fAllocated, freed.staying());
    }

    @Test
    void freedOnDiskOfOverlappingSelectionCountsEachFileOnce(@TempDir Path root) throws IOException {
        write(root.resolve("d/plain"), 100);
        ScanResult r = model.scan(root);
        long plainAllocated = entry(r, "d/plain").allocated();

        Freed freed = model.freedOnDisk(r, Set.of(root.resolve("d"), root.resolve("d/plain")));

        assertEquals(plainAllocated, freed.freed());
    }

    @Test
    void freedOnDiskOfOverlappingSelectionCountsSharedLinkOnce(@TempDir Path root) throws IOException {
        write(root.resolve("d/f"), MIB);
        link(root.resolve("e/f"), root.resolve("d/f"));
        ScanResult r = model.scan(root);
        long fAllocated = entry(r, "d/f").allocated();

        Freed freed = model.freedOnDisk(r, Set.of(root.resolve("d"), root.resolve("d/f")));

        assertEquals(0, freed.freed());
        assertEquals(fAllocated, freed.staying());
    }

    @Test
    void sharedBytesReportsLinksOwnedElsewhere(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);

        assertEquals(MIB, r.sharedBytes(root.resolve("b"), SizeMode.LOGICAL));
        assertEquals(0, r.sharedBytes(root.resolve("a"), SizeMode.LOGICAL));
        assertEquals(0, r.sharedBytes(root, SizeMode.LOGICAL));
    }

    @Test
    void cancelledRescanLeavesPreviousResultIntact(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);
        Object k = key(r, "a/f");

        Thread.currentThread().interrupt();
        try {
            assertThrows(DiskUsageModel.ScanCancelledException.class,
                    () -> model.rescan(r, root.resolve("a"), QUIET));
        } finally {
            assertTrue(Thread.interrupted(), "interrupt flag should still be set; also clears it");
        }

        assertEquals(MIB, r.root().totalSize());
        assertEquals(MIB, node(r, "a").totalSize());
        assertEquals(List.of(root.resolve("a/f"), root.resolve("b/f")), r.inodes().linksOf(k));
        assertEquals(Optional.of(root.resolve("a/f")), r.inodes().owner(k));
    }
}
