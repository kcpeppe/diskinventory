package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeEditTest {

    private static void write(Path file, int bytes) throws IOException {
        Files.write(file, new byte[bytes]);
    }

    private static DirectoryNode scan(Path root) throws IOException {
        return new DiskUsageModel().scan(root).root();
    }

    @Test
    void treeEditSharesUntouchedSubtrees(@TempDir Path root) throws IOException {
        Path a = Files.createDirectory(root.resolve("a"));
        Path x = Files.createDirectory(a.resolve("x"));
        write(x.resolve("f"), 100);
        Path b = Files.createDirectory(root.resolve("b"));
        write(b.resolve("g"), 200);

        DirectoryNode before = scan(root);
        DirectoryNode after = TreeEdit.replace(before, root.resolve("a/x/f"), null, null);

        assertSame(before.child("b").orElseThrow(), after.child("b").orElseThrow());
        assertEquals(200, after.totalSize());
        assertEquals(0, after.child("a").orElseThrow().totalSize());
    }

    @Test
    void replaceInsertsNewDirectoryAndKeepsSortOrder(@TempDir Path root) throws IOException {
        Path small = Files.createDirectory(root.resolve("small"));
        write(small.resolve("f"), 10);
        Path big = Files.createDirectory(root.resolve("big"));
        write(big.resolve("f"), 500);

        DirectoryNode before = scan(root);

        Path newDir = Files.createDirectory(root.resolve("new"));
        write(newDir.resolve("f"), 1000);
        DirectoryNode scannedNew = scan(newDir);

        DirectoryNode after = TreeEdit.replace(before, root.resolve("new"), scannedNew, null);

        assertEquals(List.of("new", "big", "small"),
                after.children().stream().map(DirectoryNode::name).toList());
        assertEquals(1510, after.totalSize());
    }

    @Test
    void replaceSwapsFileForDirectoryOfSameName(@TempDir Path root, @TempDir Path other) throws IOException {
        write(root.resolve("n"), 100);
        DirectoryNode before = scan(root);

        Path n = Files.createDirectory(other.resolve("n"));
        write(n.resolve("a"), 100);
        write(n.resolve("b"), 200);
        DirectoryNode scannedN = scan(n);

        DirectoryNode after = TreeEdit.replace(before, root.resolve("n"), scannedN, null);

        assertTrue(after.files().stream().noneMatch(f -> f.name().equals("n")));
        assertEquals(300, after.child("n").orElseThrow().totalSize());
        assertEquals(300, after.totalSize());
    }

    @Test
    void chargeAddsToDirectAndAncestorTotals(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("a"));
        Path b = Files.createDirectory(root.resolve("a").resolve("b"));
        DirectoryNode before = scan(root);

        DirectoryNode charged = TreeEdit.charge(before, Map.of(b, new TreeEdit.Charge(10, 4096)));

        DirectoryNode chargedB = charged.child("a").orElseThrow().child("b").orElseThrow();
        assertEquals(10, chargedB.directFileSize());
        assertEquals(4096, chargedB.directFileSize(SizeMode.ALLOCATED));
        assertEquals(10, charged.child("a").orElseThrow().totalSize());
        assertEquals(10, charged.totalSize());

        DirectoryNode reverted = TreeEdit.charge(charged, Map.of(b, new TreeEdit.Charge(10, 4096).negate()));
        assertEquals(0, reverted.totalSize());
        assertEquals(0, reverted.child("a").orElseThrow().child("b").orElseThrow().directFileSize());

        DirectoryNode ignored = TreeEdit.charge(before, Map.of(root.resolve("zzz"), new TreeEdit.Charge(1, 1)));
        assertSame(before, ignored);
    }

    @Test
    void replaceKeepsChargesOfParent(@TempDir Path root) throws IOException {
        write(root.resolve("a.bin"), 100);
        DirectoryNode charged = TreeEdit.charge(scan(root), Map.of(root, new TreeEdit.Charge(50, 4096)));

        DirectoryNode after = TreeEdit.replace(charged, root.resolve("a.bin"), null,
                new FileEntry("a.bin", 300, 300, 1, null));

        assertEquals(350, after.directFileSize());
        assertEquals(350, after.totalSize());
        assertEquals(300 + 4096, after.directFileSize(SizeMode.ALLOCATED));
        assertEquals(300 + 4096, after.totalSize(SizeMode.ALLOCATED));
    }

    @Test
    void chargeRoutesManyDeltasToTheirDirectories(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        Files.createDirectories(root.resolve("ab"));
        Files.createDirectories(root.resolve("c"));
        DirectoryNode before = scan(root);

        DirectoryNode after = TreeEdit.charge(before, Map.of(
                root.resolve("a/b"), new TreeEdit.Charge(1, 1),
                root.resolve("a"), new TreeEdit.Charge(10, 10),
                root.resolve("ab"), new TreeEdit.Charge(100, 100),
                root, new TreeEdit.Charge(1000, 1000)));

        assertEquals(1111, after.totalSize());
        assertEquals(1000, after.directFileSize());
        assertEquals(11, after.child("a").orElseThrow().totalSize());
        assertEquals(1, after.child("a").orElseThrow().child("b").orElseThrow().totalSize());
        assertEquals(100, after.child("ab").orElseThrow().totalSize());
        assertSame(before.child("c").orElseThrow(), after.child("c").orElseThrow());
    }

    @Test
    void replaceRejectsPathsNotUnderRoot(@TempDir Path root) throws IOException {
        DirectoryNode tree = scan(root);

        assertThrows(IllegalArgumentException.class, () -> TreeEdit.replace(tree, root, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> TreeEdit.replace(tree, root.resolve("missing/x"), null, null));
    }

    @Test
    void replaceRejectsBothDirAndFileNonNull(@TempDir Path root) throws IOException {
        DirectoryNode tree = scan(root);
        DirectoryNode dir = new DirectoryNode(root.resolve("x"), 0, 0, 0, 0, List.of(), List.of(), 0);
        FileEntry file = new FileEntry("x", 1, 1, 1, null);

        assertThrows(IllegalArgumentException.class, () -> TreeEdit.replace(tree, root.resolve("x"), dir, file));
    }

    @Test
    void sharedFileDoesNotContributeToDirectSize(@TempDir Path root) throws IOException {
        DirectoryNode before = scan(root);

        FileEntry shared = new FileEntry("f", 100, 100, 2, "key");
        DirectoryNode afterShared = TreeEdit.replace(before, root.resolve("f"), null, shared);
        assertEquals(0, afterShared.directFileSize());
        assertEquals(0, afterShared.totalSize());

        FileEntry unshared = new FileEntry("f", 100, 100, 1, null);
        DirectoryNode afterUnshared = TreeEdit.replace(before, root.resolve("f"), null, unshared);
        assertEquals(100, afterUnshared.directFileSize());
        assertEquals(100, afterUnshared.totalSize());
    }

    @Test
    void errorCountPropagatesAlongPathOnSwap(@TempDir Path root) {
        Path aPath = root.resolve("a");
        Path bPath = aPath.resolve("b");
        DirectoryNode oldB = new DirectoryNode(bPath, 0, 0, 0, 0, List.of(), List.of(), 2);
        DirectoryNode oldA = new DirectoryNode(aPath, 0, 0, 0, 0, List.of(oldB), List.of(), 2);
        DirectoryNode before = new DirectoryNode(root, 0, 0, 0, 0, List.of(oldA), List.of(), 2);

        DirectoryNode newB = new DirectoryNode(bPath, 0, 0, 0, 0, List.of(), List.of(), 5);
        DirectoryNode after = TreeEdit.replace(before, bPath, newB, null);

        assertEquals(5, after.child("a").orElseThrow().child("b").orElseThrow().errorCount());
        assertEquals(5, after.child("a").orElseThrow().errorCount());
        assertEquals(5, after.errorCount());
    }

    @Test
    void errorCountDropsWhenSubtreeWithErrorsIsDeleted(@TempDir Path root) {
        Path aPath = root.resolve("a");
        DirectoryNode oldA = new DirectoryNode(aPath, 0, 0, 0, 0, List.of(), List.of(), 4);
        DirectoryNode before = new DirectoryNode(root, 0, 0, 0, 0, List.of(oldA), List.of(), 4);

        DirectoryNode after = TreeEdit.replace(before, aPath, null, null);

        assertEquals(0, after.errorCount());
    }
}
