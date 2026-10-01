package com.kodewerk.diskinventory.model;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Headless scan API. {@link #scan(Path)} walks the tree once, summing logical
 * file sizes ({@code Files.size} semantics), and returns an immutable
 * {@link DirectoryNode} tree with its {@link InodeIndex}. A file with several
 * hardlinks in the tree is counted once, in its owner's directory. Symbolic
 * links are not followed; unreadable entries are counted, not fatal.
 */
public final class DiskUsageModel {

    /** Progress callback, invoked once per directory as the walk enters it. */
    @FunctionalInterface
    public interface ScanListener {
        void entering(Path directory, long directoriesSoFar, long bytesSoFar);
    }

    /** Thrown when the scanning thread is interrupted mid-walk. */
    public static final class ScanCancelledException extends IOException {
        ScanCancelledException() {
            super("scan cancelled");
        }
    }

    public ScanResult scan(Path root) throws IOException {
        return scan(root, (dir, dirs, bytes) -> { });
    }

    public ScanResult scan(Path root, ScanListener listener) throws IOException {
        return scan(root, listener, null, new InodeIndex());
    }

    /**
     * Scans the parent directory of an already-scanned node, grafting the known
     * subtree in place rather than re-walking it. Only the parent's other
     * children touch the disk. Shared files found there may take ownership
     * away from links in the known subtree. Returns {@code known} unchanged if
     * it is already the filesystem root; otherwise {@code known} is not modified.
     */
    public ScanResult scanParent(ScanResult known, ScanListener listener) throws IOException {
        Path parent = known.root().path().getParent();
        if (parent == null) {
            return known;
        }
        return scan(parent, listener, known.root(), known.inodes().copy());
    }

    /**
     * Re-reads one path, a directory or a single file, and folds it into the
     * tree, then re-settles the owner of every shared file that gained or lost
     * a link. A path that is gone (or is now a symlink) is dropped. Rescanning
     * the scan root is a full scan. {@code result} is not modified, also when
     * the rescan is cancelled.
     */
    public ScanResult rescan(ScanResult result, Path p, ScanListener listener) throws IOException {
        Path root = result.root().path();
        if (p.equals(root)) {
            return scan(root, listener);
        }
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("path is not under the scan root: " + p);
        }

        InodeIndex inodes = result.inodes().copy();
        Set<Object> affected = inodes.removeLinksUnder(p);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            attrs = null;
        }

        DirectoryNode dir = null;
        FileEntry file = null;
        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            if (attrs != null && attrs.isDirectory()) {
                Visitor visitor = new Visitor(listener, null, probe, inodes);
                Files.walkFileTree(p, visitor);
                dir = visitor.result();
                affected.addAll(visitor.touched());
            } else if (attrs != null && attrs.isRegularFile()) {
                file = entryFor(p, attrs, probe);
                if (file.shared()) {
                    inodes.addLink(file.fileKey(), p, file.nlink(), file.size(), file.allocated());
                    affected.add(file.fileKey());
                }
            }
        }
        DirectoryNode tree = TreeEdit.replace(result.root(), p, dir, file);
        return new ScanResult(TreeEdit.charge(tree, inodes.settle(affected)), inodes);
    }

    /**
     * What deleting {@code selection} (files and/or directories from the tree,
     * no disk access for them) would free on disk, in allocated bytes. An
     * unshared file under the selection always frees its allocated bytes. Each
     * shared inode with at least one link in the selection is re-stat'ed fresh
     * on one of its still-existing links: if its fresh {@code nlink} equals the
     * number of its links in the selection, every link is being deleted and it
     * frees; otherwise a link outside the selection survives and its bytes stay.
     * An inode whose links have all vanished on disk already is skipped.
     */
    public Freed freedOnDisk(ScanResult result, Set<Path> selection) {
        long freed = 0;
        Map<Object, Integer> selectedLinks = new HashMap<>();
        Map<Object, FileEntry> sample = new HashMap<>();
        for (Path p : topLevelOf(selection)) {
            DirectoryNode dir = findDir(result.root(), p);
            if (dir != null) {
                freed += tallyUnsharedAndCountLinks(dir, selectedLinks, sample);
                continue;
            }
            FileEntry file = findFile(result.root(), p);
            if (file == null) {
                continue;
            }
            if (file.shared()) {
                selectedLinks.merge(file.fileKey(), 1, Integer::sum);
                sample.putIfAbsent(file.fileKey(), file);
            } else {
                freed += file.allocated();
            }
        }

        long staying = 0;
        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            for (Map.Entry<Object, Integer> e : selectedLinks.entrySet()) {
                Object key = e.getKey();
                Path existing = result.inodes().linksOf(key).stream()
                        .filter(l -> Files.exists(l, NOFOLLOW_LINKS))
                        .findFirst().orElse(null);
                if (existing == null) {
                    continue;    // all links already gone on disk
                }
                long allocated = sample.get(key).allocated();
                if (freshNlink(existing, probe) == e.getValue()) {
                    freed += allocated;
                } else {
                    staying += allocated;
                }
            }
        }
        return new Freed(freed, staying);
    }

    /**
     * {@code selection} with every element dropped that sits at or under another
     * selected element, so a directory and a path inside it are never both
     * walked — each file would otherwise be tallied once directly and again as
     * part of its selected ancestor's subtree.
     */
    private static Set<Path> topLevelOf(Set<Path> selection) {
        return selection.stream()
                .filter(p -> selection.stream().noneMatch(other -> !other.equals(p) && p.startsWith(other)))
                .collect(Collectors.toSet());
    }

    /** Sums unshared files' allocated bytes under {@code dir}, and tallies shared files' links per key. */
    private static long tallyUnsharedAndCountLinks(DirectoryNode dir, Map<Object, Integer> selectedLinks,
                                                     Map<Object, FileEntry> sample) {
        long freed = 0;
        for (FileEntry f : dir.files()) {
            if (f.shared()) {
                selectedLinks.merge(f.fileKey(), 1, Integer::sum);
                sample.putIfAbsent(f.fileKey(), f);
            } else {
                freed += f.allocated();
            }
        }
        for (DirectoryNode child : dir.children()) {
            freed += tallyUnsharedAndCountLinks(child, selectedLinks, sample);
        }
        return freed;
    }

    /** The node at {@code target} in the tree rooted at {@code root}, or null if it names a file or isn't there. */
    private static DirectoryNode findDir(DirectoryNode root, Path target) {
        if (target.equals(root.path())) {
            return root;
        }
        if (!target.startsWith(root.path())) {
            return null;
        }
        DirectoryNode node = root;
        for (Path segment : root.path().relativize(target)) {
            Optional<DirectoryNode> child = node.child(segment.toString());
            if (child.isEmpty()) {
                return null;
            }
            node = child.get();
        }
        return node;
    }

    /** The file entry at {@code target} in the tree rooted at {@code root}, or null if it isn't there. */
    private static FileEntry findFile(DirectoryNode root, Path target) {
        Path parent = target.getParent();
        if (parent == null) {
            return null;
        }
        DirectoryNode dir = findDir(root, parent);
        if (dir == null) {
            return null;
        }
        String name = target.getFileName().toString();
        return dir.files().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }

    /** Fresh nlink of {@code path}, same fallback chain as {@link #entryFor}. */
    private static long freshNlink(Path path, AllocatedSizeProbe probe) {
        return probe != null ? probe.stat(path, -1L).nlink() : readNlinkFallback(path);
    }

    /**
     * Whether allocated (on-disk) sizes are real on this platform and build.
     * Performs an actual lstat downcall rather than only constructing the
     * probe: an unregistered downcall stub in a native image surfaces either
     * when the handle is created or when the call is made, depending on the
     * build, and only a real call rules out both.
     */
    public static boolean allocatedSizeSupported() {
        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            return probe != null && probe.stat(Path.of("."), -1L).allocated() >= 0L;
        }
    }

    /**
     * Builds the {@link FileEntry} for one scanned file, the single place
     * nlink and fileKey are resolved. {@code fileKey} is kept only when
     * {@code nlink > 1} (a shared file); otherwise it's dropped even if the
     * filesystem reports one.
     */
    static FileEntry entryFor(Path file, BasicFileAttributes attrs, AllocatedSizeProbe probe) {
        long size = attrs.size();
        Object fileKey = attrs.fileKey();
        long allocated;
        long nlink;
        if (probe != null) {
            AllocatedSizeProbe.Stat stat = probe.stat(file, size);
            allocated = stat.allocated();
            // No fileKey to group links by, so there's no point trusting the
            // probe's nlink beyond 1 - it would never pair with another entry.
            nlink = fileKey == null ? 1 : stat.nlink();
        } else {
            allocated = size;
            nlink = fileKey == null ? 1 : readNlinkFallback(file);
        }
        return new FileEntry(file.getFileName().toString(), size, allocated, nlink,
                nlink > 1 ? fileKey : null);
    }

    /** {@code unix:nlink} view, falling back to 1 when unsupported or unreadable (e.g. the path is gone). */
    static long readNlinkFallback(Path path) {
        try {
            return ((Number) Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS)).longValue();
        } catch (UnsupportedOperationException | IOException e) {
            return 1;
        }
    }

    private ScanResult scan(Path root, ScanListener listener, DirectoryNode graft, InodeIndex inodes)
            throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new IOException("Directory is not readable: " + root);
        }

        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            Visitor visitor = new Visitor(listener, graft, probe, inodes);
            Files.walkFileTree(root, visitor);
            DirectoryNode tree = TreeEdit.charge(visitor.result(), inodes.settle(visitor.touched()));
            return new ScanResult(tree, inodes);
        }
    }

    /** An empty node for a directory that could not be read: one error, no bytes. */
    private static DirectoryNode unreadable(Path dir) {
        return new DirectoryNode(dir, 0, 0, 0, 0, List.of(), List.of(), 1);
    }

    private static final class Building {
        final Path path;
        long directFileSize;
        long totalSize;
        long directAllocated;
        long totalAllocated;
        int errorCount;
        final List<DirectoryNode> children = new ArrayList<>();
        final List<FileEntry> files = new ArrayList<>();

        Building(Path path) {
            this.path = path;
        }
    }

    static final class Visitor extends SimpleFileVisitor<Path> {
        private final ScanListener listener;
        private final DirectoryNode graft;
        private final AllocatedSizeProbe probe;
        private final InodeIndex inodes;
        private final Set<Object> touched = new HashSet<>();
        private final Deque<Building> stack = new ArrayDeque<>();
        private long dirsSoFar;
        private long bytesSoFar;
        private long fileTally;
        private DirectoryNode result;

        Visitor(ScanListener listener, DirectoryNode graft, AllocatedSizeProbe probe, InodeIndex inodes) {
            this.listener = listener;
            this.graft = graft;
            this.probe = probe;
            this.inodes = inodes;
        }

        DirectoryNode result() {
            return result;
        }

        /** Keys of the shared files this walk found; their owners need settling. */
        Set<Object> touched() {
            return touched;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            checkCancelled();
            if (graft != null && dir.equals(graft.path())) {
                Building parent = stack.peek();
                parent.children.add(graft);
                parent.totalSize += graft.totalSize();
                parent.totalAllocated += graft.totalSize(SizeMode.ALLOCATED);
                parent.errorCount += graft.errorCount();
                bytesSoFar += graft.totalSize();
                return FileVisitResult.SKIP_SUBTREE;
            }
            dirsSoFar++;
            listener.entering(dir, dirsSoFar, bytesSoFar);
            stack.push(new Building(dir));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            // Symlinks are neither followed nor counted; only real files sum.
            if (attrs.isSymbolicLink()) {
                return FileVisitResult.CONTINUE;
            }
            if ((++fileTally & 0xFFF) == 0) {
                checkCancelled();
            }
            Building current = stack.peek();
            FileEntry entry = entryFor(file, attrs, probe);
            current.files.add(entry);
            if (entry.shared()) {
                // Counted later, once, in its owner's directory via settle().
                inodes.addLink(entry.fileKey(), file, entry.nlink(), entry.size(), entry.allocated());
                touched.add(entry.fileKey());
                return FileVisitResult.CONTINUE;
            }
            current.directFileSize += entry.size();
            current.totalSize += entry.size();
            current.directAllocated += entry.allocated();
            current.totalAllocated += entry.allocated();
            bytesSoFar += entry.size();
            return FileVisitResult.CONTINUE;
        }

        private static void checkCancelled() throws ScanCancelledException {
            if (Thread.currentThread().isInterrupted()) {
                throw new ScanCancelledException();
            }
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // An unreadable subdirectory arrives here instead of preVisitDirectory.
            // It becomes an empty node carrying the error, whether met in a walk
            // or as the rescan path itself, so a later rescan replaces the error
            // instead of adding a second one. A rescan path gone since rescan
            // read its attributes yields no node.
            if (stack.isEmpty()) {
                result = exc instanceof NoSuchFileException ? null : unreadable(file);
            } else {
                Building parent = stack.peek();
                parent.errorCount++;
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    parent.children.add(unreadable(file));
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            Building finished = stack.pop();
            if (exc != null) {
                finished.errorCount++;
            }
            finished.children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());
            finished.files.sort(Comparator.comparingLong((FileEntry f) -> f.size()).reversed());
            DirectoryNode node = new DirectoryNode(finished.path, finished.directFileSize,
                    finished.totalSize, finished.directAllocated, finished.totalAllocated,
                    finished.children, finished.files, finished.errorCount);

            Building parent = stack.peek();
            if (parent == null) {
                result = node;
            } else {
                parent.totalSize += node.totalSize();
                parent.totalAllocated += node.totalSize(SizeMode.ALLOCATED);
                parent.errorCount += node.errorCount();
                parent.children.add(node);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
