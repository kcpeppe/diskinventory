package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DeleterTest {

    private final DiskUsageModel model = new DiskUsageModel();

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }

    @Test
    void permanentDeleteRemovesTree(@TempDir Path root) throws IOException {
        write(root.resolve("t/a"), 10);
        write(root.resolve("t/s/b"), 20);

        Deleter.Outcome out = Deleter.deletePermanently(root.resolve("t"), () -> false);

        assertEquals(Deleter.Status.OK, out.status());
        assertTrue(out.failed().isEmpty());
        assertFalse(Files.exists(root.resolve("t")));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void permanentDeleteReportsPartialFailure(@TempDir Path root) throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")));
        write(root.resolve("t/ok.bin"), 5);
        write(root.resolve("t/locked/x.bin"), 5);

        ScanResult r = model.scan(root);
        Path locked = root.resolve("t/locked");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Deleter.Outcome out = Deleter.deletePermanently(root.resolve("t"), () -> false);

            assertEquals(Deleter.Status.FAILED, out.status());
            assertTrue(out.failed().contains(locked));
            assertFalse(Files.exists(root.resolve("t/ok.bin")));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxrwxrwx"));
        }

        ScanResult after = model.rescan(r, root.resolve("t"), (d, n, b) -> { });
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    void permanentDeleteStops(@TempDir Path root) throws IOException {
        Path t = root.resolve("t");
        for (int i = 0; i < 10; i++) {
            write(t.resolve("f" + i + ".bin"), 1);
        }
        ScanResult r = model.scan(root);

        AtomicInteger calls = new AtomicInteger();
        Deleter.Outcome out = Deleter.deletePermanently(t, () -> calls.incrementAndGet() > 3);

        assertEquals(Deleter.Status.STOPPED, out.status());
        long remaining;
        try (var paths = Files.list(t)) {
            remaining = paths.count();
        }
        assertEquals(7, remaining);

        ScanResult after = model.rescan(r, t, (d, n, b) -> { });
        TestTrees.assertMatchesFreshScan(model, after);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void trashDirFollowsXdgDefault() {
        assumeTrue(System.getenv("XDG_DATA_HOME") == null);

        var dir = Deleter.trashDir();

        assertTrue(dir.isPresent());
        assertEquals(Path.of(System.getProperty("user.home"), ".local/share/Trash"), dir.get());
    }
}
