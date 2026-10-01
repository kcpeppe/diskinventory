package com.kodewerk.diskinventory.ui;

import com.kodewerk.diskinventory.model.DirectoryNode;
import com.kodewerk.diskinventory.model.DiskUsageModel;
import com.kodewerk.diskinventory.model.Freed;
import com.kodewerk.diskinventory.model.SizeMode;
import com.kodewerk.diskinventory.model.Sizes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskInventoryAppTest {

    @Test
    void trailToStopsAtTheNearestExistingAncestor(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        DirectoryNode tree = new DiskUsageModel().scan(root).root();

        assertEquals(List.of(root, root.resolve("a"), root.resolve("a/b")),
                paths(DiskInventoryApp.trailTo(tree, root.resolve("a/b"))));
        assertEquals(List.of(root, root.resolve("a")),
                paths(DiskInventoryApp.trailTo(tree, root.resolve("a/gone/deeper"))));
        assertEquals(List.of(root), paths(DiskInventoryApp.trailTo(tree, root)));
        assertEquals(List.of(root), paths(DiskInventoryApp.trailTo(tree, root.getParent())));
    }

    @Test
    void rescanTargetIsTheHighestMissingPathBelowTheNearestAncestorInTheTree(@TempDir Path root)
            throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        DirectoryNode tree = new DiskUsageModel().scan(root).root();

        assertEquals(root.resolve("a/b"), DiskInventoryApp.rescanTarget(tree, root.resolve("a/b")));
        assertEquals(root.resolve("a/f"), DiskInventoryApp.rescanTarget(tree, root.resolve("a/f")));
        assertEquals(root.resolve("new"),
                DiskInventoryApp.rescanTarget(tree, root.resolve("new/share/Trash/files")));
        assertEquals(root.resolve("a/b/c"), DiskInventoryApp.rescanTarget(tree, root.resolve("a/b/c/d")));
        assertEquals(root, DiskInventoryApp.rescanTarget(tree, root));
    }

    @Test
    void onlyPathsStrictlyUnderTheScanRootAreDeletable() {
        Path root = Path.of("/scan/root");
        assertTrue(DiskInventoryApp.deletable(root, root.resolve("a/f")));
        assertFalse(DiskInventoryApp.deletable(root, root));
        assertFalse(DiskInventoryApp.deletable(root, Path.of("/scan")));
        assertFalse(DiskInventoryApp.deletable(root, Path.of("/scan/rootsibling")));
    }

    @Test
    void deleteSummarySaysWhatIsFreed() {
        List<Path> targets = List.of(Path.of("/r/a/f"));
        String permanent = DiskInventoryApp.deleteSummary(targets, 10 << 20, SizeMode.ALLOCATED, true,
                new Freed(0, 10 << 20));
        assertTrue(permanent.startsWith("/r/a/f\n"), permanent);
        assertTrue(permanent.contains("frees 0 B on disk; " + Sizes.human(10 << 20) + " stays (linked elsewhere)"), permanent);

        String freedAll = DiskInventoryApp.deleteSummary(targets, 1024, SizeMode.ALLOCATED, true, new Freed(1024, 0));
        assertFalse(freedAll.contains("stays"), freedAll);

        String trash = DiskInventoryApp.deleteSummary(targets, 1024, SizeMode.ALLOCATED, false, null);
        assertTrue(trash.contains("space is not freed until the Trash is emptied"), trash);
    }

    private static List<Path> paths(List<DirectoryNode> nodes) {
        return nodes.stream().map(DirectoryNode::path).toList();
    }
}
