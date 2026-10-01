package com.kodewerk.diskinventory.model;

/**
 * One file directly inside a directory. {@code size} is the logical (apparent)
 * size; {@code allocated} is the on-disk size. {@code fileKey} is non-null
 * only when {@code nlink > 1}, identifying a file shared via hardlinks.
 */
public record FileEntry(String name, long size, long allocated, long nlink, Object fileKey) {

    public long size(SizeMode mode) {
        return mode == SizeMode.ALLOCATED ? allocated : size;
    }

    public boolean shared() {
        return fileKey != null;
    }

    @Override
    public String toString() {
        return name + "  " + Sizes.human(size);
    }
}
