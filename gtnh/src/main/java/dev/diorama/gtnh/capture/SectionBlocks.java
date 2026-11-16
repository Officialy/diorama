package dev.diorama.gtnh.capture;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Borrowed, read-only numeric array view for immediate consumption on the source/main thread.
 * Arrays are neither copied nor exposed; their owner must keep them stable throughout capture.
 * This is not an immutable snapshot or a live-world callback and must not be sent to workers.
 */
public final class SectionBlocks implements ChunkCapture.Section {

    private final byte[] low;
    private final byte @Nullable [] b2Low;
    private final byte @Nullable [] b2High;
    private final byte @Nullable [] b3;
    private final byte[] m1Low;
    private final byte @Nullable [] m1High;
    private final byte @Nullable [] m2;

    private SectionBlocks(byte[] low, byte @Nullable [] b2Low, byte @Nullable [] b2High, byte @Nullable [] b3,
        byte[] m1Low, byte @Nullable [] m1High, byte @Nullable [] m2) {
        this.low = required(low, 4096, "low");
        this.b2Low = optional(b2Low, 2048, "b2Low");
        this.b2High = optional(b2High, 2048, "b2High");
        this.b3 = optional(b3, 4096, "b3");
        this.m1Low = required(m1Low, 2048, "m1Low");
        this.m1High = optional(m1High, 2048, "m1High");
        this.m2 = optional(m2, 4096, "m2");
        if (b2High != null && b2Low == null) throw new IllegalArgumentException("b2High requires b2Low");
        if (b3 != null && b2High == null) throw new IllegalArgumentException("b3 requires b2High");
        if (m2 != null && m1High == null) throw new IllegalArgumentException("m2 requires m1High");
    }

    /** Vanilla's unsigned low byte, optional high nibble, and four-bit metadata. */
    public static SectionBlocks vanilla(byte[] low, byte @Nullable [] add, byte[] metadata) {
        return new SectionBlocks(low, add, null, null, metadata, null, null);
    }

    /** EndlessIDs' 24-bit IDs and 16-bit metadata, with strict parent-before-child layers. */
    public static SectionBlocks endless(byte[] low, byte @Nullable [] b2Low, byte @Nullable [] b2High,
        byte @Nullable [] b3, byte[] m1Low, byte @Nullable [] m1High, byte @Nullable [] m2) {
        return new SectionBlocks(low, b2Low, b2High, b3, m1Low, m1High, m2);
    }

    @Override
    public int blockId(int cell) {
        checkCell(cell);
        return (low[cell] & 255) | (nibble(b2Low, cell) << 8)
            | (nibble(b2High, cell) << 12)
            | (unsigned(b3, cell) << 16);
    }

    @Override
    public int metadata(int cell) {
        checkCell(cell);
        return nibble(m1Low, cell) | (nibble(m1High, cell) << 4) | (unsigned(m2, cell) << 8);
    }

    private static int nibble(byte @Nullable [] values, int cell) {
        return values == null ? 0 : (values[cell >>> 1] >>> ((cell & 1) * 4)) & 15;
    }

    private static int unsigned(byte @Nullable [] values, int cell) {
        return values == null ? 0 : values[cell] & 255;
    }

    private static void checkCell(int cell) {
        if (cell < 0 || cell >= 4096) throw new IndexOutOfBoundsException("cell: " + cell);
    }

    private static byte[] required(byte[] values, int length, String name) {
        Objects.requireNonNull(values, name);
        if (values.length != length) throw new IllegalArgumentException(name + " length must be " + length);
        return values;
    }

    private static byte @Nullable [] optional(byte @Nullable [] values, int length, String name) {
        if (values != null && values.length != length)
            throw new IllegalArgumentException(name + " length must be " + length);
        return values;
    }
}
