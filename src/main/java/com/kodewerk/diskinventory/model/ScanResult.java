package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A scanned tree plus the hardlink index its shared-file charges came from. */
public record ScanResult(DirectoryNode root, InodeIndex inodes) {

    /**
     * {@link DirectoryNode#largestFiles}, with each shared row's {@code
     * otherLinks} filled in from the index: every link of the file, anywhere in
     * the tree, other than the row's own path.
     */
    public List<FileRef> largestFiles(DirectoryNode node, int limit, SizeMode mode) {
        List<FileRef> rows = node.largestFiles(limit, mode);
        List<FileRef> result = new ArrayList<>(rows.size());
        for (FileRef row : rows) {
            List<Path> others = row.file().shared()
                    ? inodes.linksOf(row.file().fileKey()).stream().filter(p -> !p.equals(row.path())).toList()
                    : List.of();
            result.add(new FileRef(row.directory(), row.file(), others));
        }
        return result;
    }

    /** Bytes, once per inode, of files with a link under {@code dir} whose owner is elsewhere. */
    public long sharedBytes(Path dir, SizeMode mode) {
        return inodes.sharedBytesUnder(dir, mode);
    }
}
