package com.kodewerk.diskinventory.model;

/**
 * What deleting a selection would do to disk usage, in allocated bytes.
 * {@code freed} is bytes the delete returns to the filesystem; {@code staying}
 * is bytes of shared files in the selection that stay on disk because a link
 * outside the selection survives.
 */
public record Freed(long freed, long staying) {
}
