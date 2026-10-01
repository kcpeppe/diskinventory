package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds only the path from an edited node up to the tree root, reusing
 * every untouched subtree by reference. The two primitives here back both
 * rescan (swap a subtree for a freshly-scanned one) and hardlink-ownership
 * moves (charge a size delta into one directory and its ancestors).
 */
final class TreeEdit {

    private TreeEdit() {
    }

    /** A logical/allocated byte delta to apply to a directory's direct size. */
    record Charge(long size, long allocated) {
        Charge plus(Charge o) {
            return new Charge(size + o.size, allocated + o.allocated);
        }

        Charge negate() {
            return new Charge(-size, -allocated);
        }
    }

    /**
     * Removes any child directory and any file entry named {@code p.getFileName()}
     * from {@code p}'s parent, then inserts {@code dir} or {@code file} (at most
     * one non-null). Only the path from the parent up to {@code root} is rebuilt;
     * every other subtree is reused by reference.
     */
    static DirectoryNode replace(DirectoryNode root, Path p, DirectoryNode dir, FileEntry file) {
        if (dir != null && file != null) {
            throw new IllegalArgumentException("at most one of dir/file may be non-null");
        }
        if (!p.startsWith(root.path()) || p.equals(root.path())) {
            throw new IllegalArgumentException("path is not strictly under root: " + p);
        }
        Path rel = root.path().relativize(p);
        List<String> names = new ArrayList<>(rel.getNameCount());
        for (Path segment : rel) {
            names.add(segment.toString());
        }
        return replaceAt(root, names, 0, dir, file);
    }

    private static DirectoryNode replaceAt(DirectoryNode node, List<String> names, int idx,
                                            DirectoryNode dir, FileEntry file) {
        String name = names.get(idx);
        if (idx == names.size() - 1) {
            return replaceEntry(node, name, dir, file);
        }
        DirectoryNode child = node.child(name)
                .orElseThrow(() -> new IllegalArgumentException("directory not in tree: " + name));
        DirectoryNode newChild = replaceAt(child, names, idx + 1, dir, file);
        return replaceChild(node, child, newChild);
    }

    private static DirectoryNode replaceEntry(DirectoryNode parent, String name,
                                               DirectoryNode newDir, FileEntry newFile) {
        List<DirectoryNode> children = new ArrayList<>(parent.children());
        DirectoryNode removedDir = null;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).name().equals(name)) {
                removedDir = children.remove(i);
                break;
            }
        }
        if (newDir != null) {
            children.add(newDir);
        }
        children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());

        List<FileEntry> files = new ArrayList<>(parent.files());
        FileEntry removedFile = null;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).name().equals(name)) {
                removedFile = files.remove(i);
                break;
            }
        }
        if (newFile != null) {
            files.add(newFile);
        }
        files.sort(Comparator.comparingLong((FileEntry f) -> f.size()).reversed());

        // Adjust by delta rather than recompute: the direct size also carries
        // charges for shared files owned here, which no file entry accounts for.
        Charge direct = unshared(newFile).plus(unshared(removedFile).negate());
        Charge child = new Charge(
                (newDir != null ? newDir.totalSize() : 0) - (removedDir != null ? removedDir.totalSize() : 0),
                (newDir != null ? newDir.totalSize(SizeMode.ALLOCATED) : 0)
                        - (removedDir != null ? removedDir.totalSize(SizeMode.ALLOCATED) : 0));

        int removedErrors = removedDir != null ? removedDir.errorCount() : 0;
        int addedErrors = newDir != null ? newDir.errorCount() : 0;
        int errorCount = parent.errorCount() - removedErrors + addedErrors;

        return new DirectoryNode(parent.path(), parent.directFileSize() + direct.size(),
                parent.totalSize() + direct.size() + child.size(),
                parent.directFileSize(SizeMode.ALLOCATED) + direct.allocated(),
                parent.totalSize(SizeMode.ALLOCATED) + direct.allocated() + child.allocated(),
                children, files, errorCount);
    }

    /** What a file adds to its directory's direct size; shared files are charged via charge() instead. */
    private static Charge unshared(FileEntry f) {
        return f == null || f.shared() ? new Charge(0, 0) : new Charge(f.size(), f.allocated());
    }

    private static DirectoryNode replaceChild(DirectoryNode parent, DirectoryNode oldChild, DirectoryNode newChild) {
        List<DirectoryNode> children = new ArrayList<>(parent.children());
        children.set(children.indexOf(oldChild), newChild);
        children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());

        long totalSize = parent.totalSize() - oldChild.totalSize() + newChild.totalSize();
        long totalAllocated = parent.totalSize(SizeMode.ALLOCATED) - oldChild.totalSize(SizeMode.ALLOCATED)
                + newChild.totalSize(SizeMode.ALLOCATED);
        int errorCount = parent.errorCount() - oldChild.errorCount() + newChild.errorCount();

        return new DirectoryNode(parent.path(), parent.directFileSize(), totalSize,
                parent.directFileSize(SizeMode.ALLOCATED), totalAllocated,
                children, parent.files(), errorCount);
    }

    /**
     * Adds each delta to its directory's direct size and to every ancestor's
     * total. Keys that don't name a directory in the tree are ignored. Each
     * affected directory is rebuilt once; everything else is reused by reference.
     */
    static DirectoryNode charge(DirectoryNode root, Map<Path, Charge> deltas) {
        Map<Path, Charge> under = new HashMap<>();
        deltas.forEach((dir, delta) -> {
            if (dir.startsWith(root.path())) {
                under.put(dir, delta);
            }
        });
        return under.isEmpty() ? root : chargeNode(root, under);
    }

    /** {@code deltas} holds only directories at or under {@code node}; each is routed to one child per level. */
    private static DirectoryNode chargeNode(DirectoryNode node, Map<Path, Charge> deltas) {
        Charge direct = null;
        int depth = node.path().getNameCount();
        Map<String, Map<Path, Charge>> byChild = new HashMap<>();
        for (Map.Entry<Path, Charge> e : deltas.entrySet()) {
            if (e.getKey().equals(node.path())) {
                direct = e.getValue();
            } else {
                byChild.computeIfAbsent(e.getKey().getName(depth).toString(), k -> new HashMap<>())
                        .put(e.getKey(), e.getValue());
            }
        }

        boolean changed = direct != null;
        List<DirectoryNode> children = new ArrayList<>(node.children());
        long totalSize = node.totalSize();
        long totalAllocated = node.totalSize(SizeMode.ALLOCATED);
        for (int i = 0; i < children.size(); i++) {
            DirectoryNode child = children.get(i);
            Map<Path, Charge> childDeltas = byChild.get(child.name());
            if (childDeltas != null) {
                DirectoryNode newChild = chargeNode(child, childDeltas);
                if (newChild != child) {
                    totalSize += newChild.totalSize() - child.totalSize();
                    totalAllocated += newChild.totalSize(SizeMode.ALLOCATED) - child.totalSize(SizeMode.ALLOCATED);
                    children.set(i, newChild);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return node;
        }

        long directFileSize = node.directFileSize();
        long directAllocated = node.directFileSize(SizeMode.ALLOCATED);
        if (direct != null) {
            directFileSize += direct.size();
            directAllocated += direct.allocated();
            totalSize += direct.size();
            totalAllocated += direct.allocated();
        }

        children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());
        return new DirectoryNode(node.path(), directFileSize, totalSize, directAllocated, totalAllocated,
                children, node.files(), node.errorCount());
    }
}
