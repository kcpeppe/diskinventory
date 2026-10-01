package com.kodewerk.diskinventory.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Removes files, either to Trash (via the platform's own trash mechanism) or
 * permanently. The caller (a background thread) decides which, polls
 * {@code stop} to let the user abort a permanent delete mid-walk, and rescans
 * the deleted path afterward regardless of outcome.
 */
public final class Deleter {

    private Deleter() {
    }

    public enum Status { OK, FAILED, STOPPED }

    public record Outcome(Status status, List<Path> failed) {
    }

    /**
     * Deletes {@code root} (a file or directory) permanently, deepest first,
     * via {@link Files#walkFileTree}. Symbolic links are not followed, so a
     * link under the tree is deleted as the link itself, never its target. A
     * failure to delete or to visit an entry is recorded in {@code failed} and
     * the walk continues. {@code stop} is polled before every deletion; once it
     * returns true the walk terminates immediately, without deleting the entry
     * it was about to delete.
     */
    public static Outcome deletePermanently(Path root, BooleanSupplier stop) {
        List<Path> failed = new ArrayList<>();
        boolean[] stopped = {false};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    return delete(file);
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failed.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        failed.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    return delete(dir);
                }

                private FileVisitResult delete(Path p) {
                    if (stop.getAsBoolean()) {
                        stopped[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        failed.add(p);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // walkFileTree only throws here if the starting path itself can't be
            // read at all (visitFileFailed handles every other per-entry failure).
            failed.add(root);
        }
        if (stopped[0]) {
            return new Outcome(Status.STOPPED, failed);
        }
        return new Outcome(failed.isEmpty() ? Status.OK : Status.FAILED, failed);
    }

    /** Whether {@link #trash} can be used on this machine. */
    public static boolean trashAvailable() {
        if (isMac()) {
            return true;
        }
        if (!isLinux()) {
            return false;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            if (Files.isExecutable(Path.of(dir, "gio"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves {@code p} to the platform Trash: {@code gio trash} on Linux, Finder
     * (via {@code osascript}) on macOS. The path is always passed as an
     * argument, never interpolated into a script string. A non-zero exit is
     * reported as an {@link IOException} carrying the process's output.
     */
    public static void trash(Path p) throws IOException {
        ProcessBuilder pb = isMac()
                ? new ProcessBuilder("osascript",
                        "-e", "on run argv",
                        "-e", "tell application \"Finder\" to delete (POSIX file (item 1 of argv))",
                        "-e", "end run",
                        p.toString())
                : new ProcessBuilder("gio", "trash", p.toString());
        run(pb);
    }

    /**
     * A trash call is a single call that can't be stopped, and on macOS the
     * first one blocks on a one-time Automation consent prompt the user may
     * take a while to answer — so this waits indefinitely rather than on a
     * timeout.
     */
    private static void run(ProcessBuilder pb) throws IOException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("trash: interrupted", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("trash: " + output.strip());
        }
    }

    /** The platform Trash directory, or empty if there isn't a known one. */
    public static Optional<Path> trashDir() {
        if (isMac()) {
            return Optional.of(Path.of(System.getProperty("user.home"), ".Trash"));
        }
        if (isLinux()) {
            String xdg = System.getenv("XDG_DATA_HOME");
            Path base = xdg != null && !xdg.isBlank()
                    ? Path.of(xdg)
                    : Path.of(System.getProperty("user.home"), ".local/share");
            return Optional.of(base.resolve("Trash"));
        }
        return Optional.empty();
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
