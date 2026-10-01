package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.List;

/**
 * A file located within a scanned tree: its directory plus its entry.
 * {@code otherLinks} holds the file's other link paths, empty for an unshared
 * file or when the caller didn't resolve them (see {@link DirectoryNode#largestFiles}).
 */
public record FileRef(Path directory, FileEntry file, List<Path> otherLinks) {

    public Path path() {
        return directory.resolve(file.name());
    }
}
