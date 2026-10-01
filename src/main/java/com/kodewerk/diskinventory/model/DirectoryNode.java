package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * One directory in a completed scan. Immutable; the whole tree is built in a
 * single pass so drill-down never touches the disk again. Both logical
 * (apparent) and allocated (on-disk) sizes are tracked; no-argument accessors
 * report logical sizes.
 */
public final class DirectoryNode {

    private final Path path;
    private final long directFileSize;
    private final long totalSize;
    private final long directAllocated;
    private final long totalAllocated;
    private final List<DirectoryNode> children;
    private final List<FileEntry> files;
    private final int errorCount;

    DirectoryNode(Path path, long directFileSize, long totalSize,
                  long directAllocated, long totalAllocated,
                  List<DirectoryNode> children, List<FileEntry> files, int errorCount) {
        this.path = path;
        this.directFileSize = directFileSize;
        this.totalSize = totalSize;
        this.directAllocated = directAllocated;
        this.totalAllocated = totalAllocated;
        this.children = List.copyOf(children);
        this.files = List.copyOf(files);
        this.errorCount = errorCount;
    }

    public Path path() {
        return path;
    }

    public String name() {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    /** Logical bytes in files directly in this directory (excluding subdirectories). */
    public long directFileSize() {
        return directFileSize;
    }

    /** Logical recursive total: directFileSize plus the totalSize of every child. */
    public long totalSize() {
        return totalSize;
    }

    public long directFileSize(SizeMode mode) {
        return mode == SizeMode.ALLOCATED ? directAllocated : directFileSize;
    }

    public long totalSize(SizeMode mode) {
        return mode == SizeMode.ALLOCATED ? totalAllocated : totalSize;
    }

    /** Child directories, sorted by logical totalSize descending. */
    public List<DirectoryNode> children() {
        return children;
    }

    /** Files directly in this directory, sorted by logical size descending. */
    public List<FileEntry> files() {
        return files;
    }

    /** Entries under this node (recursive) that could not be read. */
    public int errorCount() {
        return errorCount;
    }

    public Optional<DirectoryNode> child(String name) {
        return children.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /**
     * The {@code limit} largest files anywhere under this node (recursive),
     * biggest first in the given mode. A shared file appears once, under its
     * smallest path within this node (its other links elsewhere in the subtree
     * are dropped); {@code otherLinks} is left empty here since a {@link
     * DirectoryNode} has no index to resolve them — see {@link
     * ScanResult#largestFiles}. Bounded min-heap over unshared files,
     * O(files · log limit); shared files are deduped by fileKey first and fed
     * into the same heap at the end. A shared file smaller than everything in
     * a full heap can never make the cut, so it is skipped before the dedup
     * (pnpm/Nix homes have many small shared files).
     */
    public List<FileRef> largestFiles(int limit, SizeMode mode) {
        if (limit <= 0) {
            return List.of();
        }
        PriorityQueue<FileRef> heap = new PriorityQueue<>(
                Comparator.comparingLong(r -> r.file().size(mode)));
        // Best link so far per shared file, with its resolved path so each link resolves once.
        record Best(Path path, FileRef ref) {
        }
        Map<Object, Best> sharedBest = new HashMap<>();
        Deque<DirectoryNode> pending = new ArrayDeque<>();
        pending.push(this);
        while (!pending.isEmpty()) {
            DirectoryNode node = pending.pop();
            for (FileEntry file : node.files) {
                if (file.shared()) {
                    // The heap's minimum only grows once full, so a skipped file stays out.
                    if (heap.size() == limit && file.size(mode) < heap.peek().file().size(mode)) {
                        continue;
                    }
                    Best best = sharedBest.get(file.fileKey());
                    Path path = node.path.resolve(file.name());
                    if (best == null || path.compareTo(best.path()) < 0) {
                        sharedBest.put(file.fileKey(), new Best(path, new FileRef(node.path, file, List.of())));
                    }
                } else {
                    heap.add(new FileRef(node.path, file, List.of()));
                    if (heap.size() > limit) {
                        heap.poll();
                    }
                }
            }
            node.children.forEach(pending::push);
        }
        for (Best best : sharedBest.values()) {
            heap.add(best.ref());
            if (heap.size() > limit) {
                heap.poll();
            }
        }
        List<FileRef> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingLong((FileRef r) -> r.file().size(mode)).reversed());
        return result;
    }

    @Override
    public String toString() {
        return name() + " (" + Sizes.human(totalSize) + ")";
    }
}
