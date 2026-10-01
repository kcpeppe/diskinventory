package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskUsageModelTest {

    private final DiskUsageModel model = new DiskUsageModel();

    private static void write(Path file, int bytes) throws IOException {
        Files.write(file, new byte[bytes]);
    }

    private static FileEntry entry(DirectoryNode node, String name) {
        return node.files().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void sumsFilesAndSubdirectoriesRecursively(@TempDir Path root) throws IOException {
        write(root.resolve("a.bin"), 100);
        write(root.resolve("b.bin"), 50);

        Path sub = Files.createDirectory(root.resolve("sub"));
        write(sub.resolve("c.bin"), 300);

        Path nested = Files.createDirectory(sub.resolve("nested"));
        write(nested.resolve("d.bin"), 25);

        DirectoryNode result = model.scan(root).root();

        assertEquals(150, result.directFileSize());
        assertEquals(475, result.totalSize());
        assertEquals(1, result.children().size());

        DirectoryNode subNode = result.child("sub").orElseThrow();
        assertEquals(300, subNode.directFileSize());
        assertEquals(325, subNode.totalSize());

        DirectoryNode nestedNode = subNode.child("nested").orElseThrow();
        assertEquals(25, nestedNode.totalSize());
        assertTrue(nestedNode.children().isEmpty());
        assertEquals(0, result.errorCount());
    }

    @Test
    void childrenSortedBySizeDescending(@TempDir Path root) throws IOException {
        for (int i = 1; i <= 3; i++) {
            Path dir = Files.createDirectory(root.resolve("dir" + i));
            write(dir.resolve("f.bin"), i * 100);
        }

        List<DirectoryNode> children = model.scan(root).root().children();

        assertEquals(List.of("dir3", "dir2", "dir1"),
                children.stream().map(DirectoryNode::name).toList());
    }

    @Test
    void slicesAlwaysSumToTheTotal(@TempDir Path root) throws IOException {
        write(root.resolve("loose.bin"), 7);
        Path a = Files.createDirectory(root.resolve("a"));
        write(a.resolve("x.bin"), 11);
        Path b = Files.createDirectory(root.resolve("b"));
        write(b.resolve("y.bin"), 13);

        DirectoryNode result = model.scan(root).root();

        long childSum = result.children().stream().mapToLong(DirectoryNode::totalSize).sum();
        assertEquals(result.totalSize(), result.directFileSize() + childSum);
    }

    @Test
    void emptyDirectoryIsZero(@TempDir Path root) throws IOException {
        DirectoryNode result = model.scan(root).root();

        assertEquals(0, result.totalSize());
        assertEquals(0, result.directFileSize());
        assertTrue(result.children().isEmpty());
    }

    @Test
    void symlinksAreNeitherFollowedNorCounted(@TempDir Path root) throws IOException {
        Path real = Files.createDirectory(root.resolve("real"));
        write(real.resolve("big.bin"), 1000);
        write(root.resolve("plain.bin"), 30);
        Files.createSymbolicLink(root.resolve("dirlink"), real);
        Files.createSymbolicLink(root.resolve("filelink"), real.resolve("big.bin"));

        DirectoryNode result = model.scan(root).root();

        // Only the real directory and the real files contribute.
        assertEquals(1, result.children().size());
        assertEquals(1030, result.totalSize());
        assertEquals(30, result.directFileSize());
        assertEquals(List.of("plain.bin"),
                result.files().stream().map(FileEntry::name).toList());
        assertEquals(1000, result.child("real").orElseThrow().totalSize());
    }

    @Test
    void listenerSeesEveryDirectory(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("one"));
        Files.createDirectory(root.resolve("two"));

        AtomicInteger visits = new AtomicInteger();
        model.scan(root, (dir, dirs, bytes) -> visits.incrementAndGet());

        assertEquals(3, visits.get());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})    // Windows has no probe; allocated falls back to logical
    void sparseFileAllocatedSizeIsSmallerThanLogical(@TempDir Path root) throws IOException {
        write(root.resolve("dense.bin"), 65536);
        try (var raf = new java.io.RandomAccessFile(root.resolve("sparse.bin").toFile(), "rw")) {
            raf.setLength(16 * 1024 * 1024);    // a hole: no blocks written
        }

        DirectoryNode result = model.scan(root).root();

        assertEquals(65536 + 16 * 1024 * 1024, result.totalSize(SizeMode.LOGICAL));

        FileEntry dense = result.files().stream()
                .filter(f -> f.name().equals("dense.bin")).findFirst().orElseThrow();
        FileEntry sparse = result.files().stream()
                .filter(f -> f.name().equals("sparse.bin")).findFirst().orElseThrow();

        assertTrue(dense.allocated() >= dense.size(),
                "dense file should occupy at least its logical size, was " + dense.allocated());
        assertTrue(sparse.allocated() < sparse.size() / 2,
                "sparse file should occupy far less than its 16 MiB logical size, was " + sparse.allocated());
        assertEquals(dense.allocated() + sparse.allocated(), result.totalSize(SizeMode.ALLOCATED));
        assertEquals(result.directFileSize(SizeMode.ALLOCATED), result.totalSize(SizeMode.ALLOCATED));
    }

    @Test
    void allocatedTotalsAggregateThroughGraft(@TempDir Path parent) throws IOException {
        Path child = Files.createDirectory(parent.resolve("child"));
        write(child.resolve("c.bin"), 4096);
        write(parent.resolve("p.bin"), 4096);

        ScanResult knownResult = model.scan(child);
        DirectoryNode known = knownResult.root();
        DirectoryNode result = model.scanParent(knownResult, (d, n, b) -> { }).root();

        assertEquals(known.totalSize(SizeMode.ALLOCATED) + result.directFileSize(SizeMode.ALLOCATED),
                result.totalSize(SizeMode.ALLOCATED));
        assertTrue(result.totalSize(SizeMode.ALLOCATED) >= 8192);
    }

    @Test
    void largestFilesRanksRecursivelyAcrossTheSubtree(@TempDir Path root) throws IOException {
        write(root.resolve("mid.bin"), 500);
        Path a = Files.createDirectory(root.resolve("a"));
        write(a.resolve("big.bin"), 900);
        Path deep = Files.createDirectory(a.resolve("deep"));
        write(deep.resolve("biggest.bin"), 1000);
        Path b = Files.createDirectory(root.resolve("b"));
        write(b.resolve("small.bin"), 100);

        DirectoryNode result = model.scan(root).root();

        List<FileRef> top3 = result.largestFiles(3, SizeMode.LOGICAL);
        assertEquals(List.of("biggest.bin", "big.bin", "mid.bin"),
                top3.stream().map(r -> r.file().name()).toList());
        assertEquals(root.resolve("a").resolve("deep").resolve("biggest.bin"),
                top3.getFirst().path());

        assertEquals(4, result.largestFiles(100, SizeMode.LOGICAL).size());
        assertTrue(result.largestFiles(0, SizeMode.LOGICAL).isEmpty());
    }

    @Test
    void interruptCancelsTheScan(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("one"));

        Thread.currentThread().interrupt();
        try {
            assertThrows(DiskUsageModel.ScanCancelledException.class, () -> model.scan(root));
        } finally {
            assertTrue(Thread.interrupted(), "interrupt flag should still be set; also clears it");
        }
    }

    @Test
    void filesRetainedAndSortedBySizeDescending(@TempDir Path root) throws IOException {
        write(root.resolve("small.bin"), 10);
        write(root.resolve("large.bin"), 500);
        write(root.resolve("medium.bin"), 100);

        DirectoryNode result = model.scan(root).root();

        assertEquals(List.of("large.bin", "medium.bin", "small.bin"),
                result.files().stream().map(FileEntry::name).toList());
        assertEquals(610, result.files().stream().mapToLong(FileEntry::size).sum());
        assertEquals(result.directFileSize(),
                result.files().stream().mapToLong(FileEntry::size).sum());
    }

    @Test
    void scanParentGraftsKnownSubtreeWithoutRewalkingIt(@TempDir Path parent) throws IOException {
        Path child = Files.createDirectory(parent.resolve("child"));
        write(child.resolve("c.bin"), 200);
        Path sibling = Files.createDirectory(parent.resolve("sibling"));
        write(sibling.resolve("s.bin"), 300);
        write(parent.resolve("loose.bin"), 50);

        ScanResult knownResult = model.scan(child);
        DirectoryNode known = knownResult.root();

        AtomicInteger visited = new AtomicInteger();
        DirectoryNode result = model.scanParent(knownResult, (dir, dirs, bytes) -> visited.incrementAndGet()).root();

        assertEquals(parent, result.path());
        assertEquals(550, result.totalSize());
        assertEquals(50, result.directFileSize());
        // The known subtree is grafted by reference, not re-scanned.
        assertSame(known, result.child("child").orElseThrow());
        assertEquals(300, result.child("sibling").orElseThrow().totalSize());
        // Walk entered parent and sibling only — never the known child.
        assertEquals(2, visited.get());
    }

    @Test
    void scanParentOfFilesystemRootReturnsSameNode() throws IOException {
        DirectoryNode fsRoot = new DirectoryNode(Path.of("/"), 0, 0, 0, 0, List.of(), List.of(), 0);

        ScanResult known = new ScanResult(fsRoot, new InodeIndex());

        assertSame(known, model.scanParent(known, (d, n, b) -> { }));
    }

    @Test
    void rejectsNonDirectory(@TempDir Path root) throws IOException {
        Path file = root.resolve("plain.txt");
        write(file, 1);

        assertThrows(IOException.class, () -> model.scan(file));
        assertThrows(IOException.class, () -> model.scan(root.resolve("missing")));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})    // Windows has no probe
    void allocatedSizesAreSupportedOnMacAndLinux() {
        assertTrue(DiskUsageModel.allocatedSizeSupported());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void sharedFileEntriesCarryNlinkAndFileKey(@TempDir Path root) throws IOException {
        write(root.resolve("a"), 100);
        Files.createLink(root.resolve("b"), root.resolve("a"));
        write(root.resolve("c"), 100);
        DirectoryNode result = model.scan(root).root();
        FileEntry a = entry(result, "a"), b = entry(result, "b"), c = entry(result, "c");
        assertEquals(2, a.nlink());
        assertEquals(a.fileKey(), b.fileKey());
        assertTrue(a.shared());
        assertFalse(c.shared());
        assertEquals(1, c.nlink());
    }

    private static final DiskUsageModel.ScanListener QUIET = (d, n, b) -> { };

    @Test
    void rescanPicksUpNewFiles(@TempDir Path root) throws IOException {
        Path sub = Files.createDirectory(root.resolve("sub"));
        write(sub.resolve("old.bin"), 100);
        ScanResult r = model.scan(root);

        write(sub.resolve("new.bin"), 300);
        ScanResult after = model.rescan(r, sub, QUIET);

        assertEquals(r.root().totalSize() + 300, after.root().totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOfVanishedPathDropsIt(@TempDir Path root) throws IOException {
        Path sub = Files.createDirectories(root.resolve("sub/deep"));
        write(sub.resolve("x.bin"), 100);
        write(root.resolve("keep.bin"), 50);
        ScanResult r = model.scan(root);

        try (var walk = Files.walk(root.resolve("sub"))) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        ScanResult after = model.rescan(r, root.resolve("sub"), QUIET);

        assertTrue(after.root().child("sub").isEmpty());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOfNewPathInsertsIt(@TempDir Path root) throws IOException {
        ScanResult r = model.scan(root);

        Path fresh = Files.createDirectory(root.resolve("fresh"));
        write(fresh.resolve("x.bin"), 200);
        ScanResult after = model.rescan(r, fresh, QUIET);

        assertEquals(200, after.root().child("fresh").orElseThrow().totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanWhenFileBecameDirectory(@TempDir Path root) throws IOException {
        write(root.resolve("n"), 100);
        ScanResult r = model.scan(root);

        Files.delete(root.resolve("n"));
        Files.createDirectory(root.resolve("n"));
        write(root.resolve("n/y.bin"), 300);
        ScanResult after = model.rescan(r, root.resolve("n"), QUIET);

        assertTrue(after.root().files().stream().noneMatch(f -> f.name().equals("n")));
        assertEquals(300, after.root().child("n").orElseThrow().totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOfSingleFile(@TempDir Path root) throws IOException {
        write(root.resolve("a.bin"), 100);
        write(root.resolve("b.bin"), 10);
        ScanResult r = model.scan(root);

        write(root.resolve("a.bin"), 900);
        ScanResult after = model.rescan(r, root.resolve("a.bin"), QUIET);

        assertEquals(910, after.root().directFileSize());
        assertEquals(900, entry(after.root(), "a.bin").size());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOfScanRootIsFullScan(@TempDir Path root) throws IOException {
        ScanResult r = model.scan(root);

        write(root.resolve("a.bin"), 100);
        ScanResult after = model.rescan(r, root, QUIET);

        assertEquals(100, after.root().totalSize());
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void rescanOutsideScanRootRejected(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("root"));
        ScanResult r = model.scan(root);

        assertThrows(IllegalArgumentException.class, () -> model.rescan(r, parent, QUIET));
        assertThrows(IllegalArgumentException.class, () -> model.rescan(r, parent.resolve("rootx"), QUIET));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void rescanOfUnreadableDirectoryCountsAnError(@TempDir Path root) throws IOException {
        Path locked = Files.createDirectory(root.resolve("locked"));
        write(locked.resolve("x.bin"), 100);
        ScanResult r = model.scan(root);

        Files.setPosixFilePermissions(locked, Set.of());
        try {
            assumeFalse(Files.isReadable(locked), "running as root");
            ScanResult after = model.rescan(r, locked, QUIET);
            assertEquals(0, after.root().totalSize());
            assertEquals(1, after.root().errorCount());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void directoryUnreadableAtScanIsCountedOnceAfterRescan(@TempDir Path root) throws IOException {
        Path locked = Files.createDirectory(root.resolve("locked"));
        write(locked.resolve("x.bin"), 100);
        Files.setPosixFilePermissions(locked, Set.of());
        try {
            assumeFalse(Files.isReadable(locked), "running as root");
            ScanResult r = model.scan(root);
            assertEquals(1, r.root().errorCount());

            ScanResult after = model.rescan(r, locked, QUIET);

            assertEquals(1, after.root().errorCount());
            TestTrees.assertMatchesFreshScan(model, after);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void rescanWalkThatFindsThePathGoneYieldsNoNode(@TempDir Path root) {
        // p vanishing between rescan's readAttributes and its walk: the walk's first callback.
        DiskUsageModel.Visitor gone = new DiskUsageModel.Visitor((d, n, b) -> { }, null, null, new InodeIndex());
        gone.visitFileFailed(root.resolve("p"), new NoSuchFileException(root.resolve("p").toString()));
        assertNull(gone.result());

        DiskUsageModel.Visitor denied = new DiskUsageModel.Visitor((d, n, b) -> { }, null, null, new InodeIndex());
        denied.visitFileFailed(root.resolve("p"), new AccessDeniedException(root.resolve("p").toString()));
        assertEquals(1, denied.result().errorCount());
    }
}
