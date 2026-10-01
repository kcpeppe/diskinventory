package com.kodewerk.diskinventory.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Filesystem-backed tree node: children (subdirectories only, symlinks
 * excluded) are listed on first access — one readdir per expansion, no
 * recursive scanning. Unreadable directories simply show no children.
 */
final class LazyDirectoryItem extends TreeItem<Path> {

    private boolean loaded;

    LazyDirectoryItem(Path path) {
        super(path);
    }

    @Override
    public boolean isLeaf() {
        return loaded && super.getChildren().isEmpty();
    }

    @Override
    public ObservableList<TreeItem<Path>> getChildren() {
        if (!loaded) {
            loaded = true;
            List<TreeItem<Path>> subdirs = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(getValue(),
                    p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(p))) {
                for (Path dir : stream) {
                    subdirs.add(new LazyDirectoryItem(dir));
                }
            } catch (IOException ignored) {
                // Unreadable: present as empty rather than failing the tree.
            }
            subdirs.sort(Comparator.comparing(
                    item -> item.getValue().getFileName().toString(),
                    String.CASE_INSENSITIVE_ORDER));
            super.getChildren().setAll(subdirs);
        }
        return super.getChildren();
    }

    /** Forgets the listing; it is re-read on the next {@link #getChildren()}, at once if expanded. */
    void reload() {
        loaded = false;
        if (isExpanded()) {
            getChildren();
        }
    }
}
