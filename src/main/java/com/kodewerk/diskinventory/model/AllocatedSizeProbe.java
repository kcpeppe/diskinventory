package com.kodewerk.diskinventory.model;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Reads allocated on-disk size (st_blocks * 512, what {@code du} reports) and
 * link count (st_nlink) via an FFM {@code lstat} downcall — the JDK's
 * attribute views do not expose block counts. One instance per scan; confined
 * to the scanning thread. If the platform is unsupported or any call fails,
 * callers fall back to the logical size and an nlink of 1.
 */
final class AllocatedSizeProbe implements AutoCloseable {

    /** Allocated bytes and hardlink count read in one lstat call. */
    record Stat(long allocated, long nlink) {
    }

    private static final int STAT_BUF_BYTES = 256;
    private static final int PATH_BUF_BYTES = 8192;
    private static final long DEV_BSIZE = 512;

    private final Arena arena;
    private final MethodHandle lstat;
    private final MemorySegment statBuf;
    private final MemorySegment pathBuf;
    private final long blocksOffset;
    private final long nlinkOffset;
    /** 0 when the st_nlink layout is unknown: nlink then comes from {@code unix:nlink}. */
    private final int nlinkWidthBytes;

    private AllocatedSizeProbe(Arena arena, MethodHandle lstat, long blocksOffset,
                                long nlinkOffset, int nlinkWidthBytes) {
        this.arena = arena;
        this.lstat = lstat;
        this.blocksOffset = blocksOffset;
        this.nlinkOffset = nlinkOffset;
        this.nlinkWidthBytes = nlinkWidthBytes;
        this.statBuf = arena.allocate(STAT_BUF_BYTES);
        this.pathBuf = arena.allocate(PATH_BUF_BYTES);
    }

    /** Returns a probe for this platform, or null if unsupported. */
    static AllocatedSizeProbe create() {
        return create(nlinkLayoutForOs());
    }

    /**
     * A probe with the given {offset, widthBytes} of st_nlink, or null for an
     * unknown layout: allocated sizes still come from lstat, nlink from the
     * {@code unix:nlink} fallback.
     */
    static AllocatedSizeProbe create(long[] nlinkLayout) {
        long blocksOffset = blocksOffsetForOs();
        if (blocksOffset < 0) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            // On x86_64 darwin the plain "lstat" symbol uses the legacy
            // 32-bit-inode struct layout; the modern layout (blocks @104) is
            // "lstat$INODE64". arm64 has no $INODE64 variants - its "lstat"
            // is already the modern struct. Prefer $INODE64, fall back.
            MemorySegment symbol = linker.defaultLookup().find("lstat$INODE64")
                    .or(() -> linker.defaultLookup().find("lstat"))
                    .orElseThrow();
            MethodHandle handle = linker.downcallHandle(symbol,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            return nlinkLayout == null
                    ? new AllocatedSizeProbe(Arena.ofConfined(), handle, blocksOffset, -1, 0)
                    : new AllocatedSizeProbe(Arena.ofConfined(), handle, blocksOffset,
                            nlinkLayout[0], (int) nlinkLayout[1]);
        } catch (Throwable t) {
            // Native images throw MissingForeignRegistrationError (an Error, not a
            // RuntimeException) when the lstat downcall stub wasn't registered at
            // build time, so this must catch Throwable to degrade instead of crashing.
            return null;
        }
    }

    private static long blocksOffsetForOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return 104;     // darwin 64-bit struct stat: st_size @96, st_blocks @104
        }
        if (os.contains("linux")) {
            return 64;      // linux x86_64/aarch64 struct stat: st_size @48, st_blocks @64
        }
        return -1;
    }

    /** {offset, widthBytes} of st_nlink for this OS/arch, or null if unsupported. */
    private static long[] nlinkLayoutForOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new long[]{6, 2};    // darwin 64-bit struct stat: st_nlink @6, u16
        }
        if (os.contains("linux")) {
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (arch.contains("aarch64")) {
                return new long[]{20, 4};   // linux aarch64: st_nlink @20, u32
            }
            if (arch.contains("amd64") || arch.contains("x86_64")) {
                return new long[]{16, 8};   // linux x86_64: st_nlink @16, u64
            }
            return null;    // unknown arch: st_nlink layout not known, use the unix:nlink fallback
        }
        return null;
    }

    /** Stat of the file at {@code path}, or {@code Stat(fallbackAllocated, 1)} on any failure. */
    Stat stat(Path path, long fallbackAllocated) {
        try {
            String p = path.toString();
            if (p.getBytes(StandardCharsets.UTF_8).length >= PATH_BUF_BYTES) {
                return new Stat(fallbackAllocated, 1);
            }
            pathBuf.setString(0, p);
            int rc = (int) lstat.invokeExact(pathBuf, statBuf);
            if (rc != 0) {
                return new Stat(fallbackAllocated, 1);
            }
            long allocated = statBuf.get(JAVA_LONG, blocksOffset) * DEV_BSIZE;
            long nlink = switch (nlinkWidthBytes) {
                case 0 -> DiskUsageModel.readNlinkFallback(path);
                case 2 -> statBuf.get(JAVA_SHORT, nlinkOffset) & 0xFFFF;
                case 4 -> statBuf.get(JAVA_INT, nlinkOffset) & 0xFFFFFFFFL;
                default -> statBuf.get(JAVA_LONG, nlinkOffset);
            };
            return new Stat(allocated, nlink);
        } catch (Throwable t) {
            return new Stat(fallbackAllocated, 1);
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
