package dev.diorama.data;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Prepared numeric CPU input, not a capture of a live Minecraft chunk. Inputs must already have
 * their final view coordinates, look IDs, light and blended tints. No world objects are retained.
 */
public final class ChunkSnapshot {

    public static final int NETHER_WASTES_FOG = 0x330808;
    public final int chunkX;
    public final int chunkZ;
    public final int minSectionY;
    public final int maxY;
    private final int airId;
    private final Object lookDomain;
    private final int[] @Nullable [] sections;
    private final @Nullable Object[] densePresence;
    private final @Nullable Object[] uniformPresence;
    private final byte[] @Nullable [] blockLight;
    private final byte[] @Nullable [] skyLight;
    private final boolean hasSkyLight;
    private final int[] surface;
    private final int fogRGB;
    private final Tints tints;

    /**
     * Copies every supplied array. Section entries are null for known air, one ID for an explicit
     * uniform section, or 4096 IDs in (y << 8) | (z << 4) | x order. Light entries are null or 4096
     * unpacked values in 0..15. Surface is 256 column tops; maxY is the prepared highest non-air y,
     * or Integer.MIN_VALUE, not independently captured or derived here. The look-domain identity
     * must match the reader's explicit session. Returned tint arrays are read-only by contract.
     */
    public ChunkSnapshot(int chunkX, int chunkZ, int minSectionY, int airId, Object lookDomain,
        int[] @Nullable [] sectionIds, byte[] @Nullable [] blockLight, byte[] @Nullable [] skyLight,
        boolean hasSkyLight, int[] surface, int maxY, int fogRGB, Tints tints) {
        if (airId < 0) throw new IllegalArgumentException("Negative air ID");
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSectionY = minSectionY;
        this.airId = airId;
        this.lookDomain = Objects.requireNonNull(lookDomain, "lookDomain");
        this.maxY = maxY;
        this.hasSkyLight = hasSkyLight;
        this.surface = copyColumns(surface);
        this.fogRGB = fogRGB & 0xFFFFFF;
        this.tints = new Tints(tints.grass(), tints.foliage(), tints.dryFoliage(), tints.water());
        this.sections = new int[sectionIds.length][];
        this.densePresence = new Object[sectionIds.length];
        this.uniformPresence = new Object[sectionIds.length];
        for (int i = 0; i < sectionIds.length; i++) {
            int[] ids = sectionIds[i];
            if (ids == null) continue;
            if (ids.length != 1 && ids.length != 4096)
                throw new IllegalArgumentException("Section must be uniform or 4096 IDs");
            int[] copy = ids.clone();
            for (int id : copy)
                if (id < 0) throw new IllegalArgumentException("Snapshot IDs must be known and nonnegative");
            this.sections[i] = copy;
            if (copy.length == 1) this.uniformPresence[i] = copy;
            else this.densePresence[i] = copy;
        }
        this.blockLight = copyLight(blockLight, sectionIds.length);
        this.skyLight = copyLight(skyLight, sectionIds.length);
    }

    private static byte[] @Nullable [] copyLight(byte[] @Nullable [] from, int sections) {
        if (from.length != sections) throw new IllegalArgumentException("Light section count mismatch");
        byte[][] copy = new byte[sections][];
        for (int i = 0; i < sections; i++) {
            byte[] layer = from[i];
            if (layer == null) continue;
            if (layer.length != 4096) throw new IllegalArgumentException("Light layer must have 4096 values");
            copy[i] = layer.clone();
            for (byte light : copy[i])
                if (light < 0 || light > 15) throw new IllegalArgumentException("Light outside 0..15");
        }
        return copy;
    }

    private static int[] copyColumns(int[] columns) {
        if (columns.length != 256) throw new IllegalArgumentException("Expected 256 columns");
        return columns.clone();
    }

    public int airId() {
        return this.airId;
    }

    public Object lookDomain() {
        return this.lookDomain;
    }

    public int sectionCount() {
        return this.sections.length;
    }

    /** A private job copy of the prepared descriptor, or null for known air. */
    public int @Nullable [] sectionIds(int index) {
        int[] ids = this.sections[index];
        return ids == null ? null : ids.clone();
    }

    public int blockLight(int sectionIndex, int x, int y, int z) {
        byte[] layer = this.blockLight[sectionIndex];
        return layer == null ? 0 : layer[(y << 8) | (z << 4) | x];
    }

    public int skyLight(int sectionIndex, int x, int y, int z) {
        if (!this.hasSkyLight) return 0;
        byte[] layer = this.skyLight[sectionIndex];
        if (layer != null) return layer[(y << 8) | (z << 4) | x];
        // Captured sky light has the same absent-section fallback as the modern snapshot.
        for (int i = sectionIndex + 1; i < this.skyLight.length; i++) {
            if (this.skyLight[i] != null) return this.skyLight[i][(z << 4) | x];
        }
        return 15;
    }

    public int topBelow(int ceiling) {
        return SnapshotHeights.topBelow(this.maxY, this.surface, ceiling);
    }

    public int firstSectionFrom(int y) {
        return SnapshotHeights.firstSectionFrom(this.minSectionY, this.densePresence, this.uniformPresence, y);
    }

    public int lastSectionTopBelow(int y) {
        return SnapshotHeights.lastSectionTopBelow(this.minSectionY, this.densePresence, this.uniformPresence, y);
    }

    public int surface(int x, int z) {
        return this.surface[((z & 15) << 4) | (x & 15)];
    }

    /** One prepared chunk fog RGB; no biome lookup or synthetic palette is supplied here. */
    public int fogColor(int quartX, int quartY, int quartZ) {
        return this.fogRGB;
    }

    public Tints tints() {
        return this.tints;
    }

    public Tints bakeTints() {
        return this.tints;
    }

    /** Prepared blended RGB inputs. Array accessors are read-only by contract, as on modern targets. */
    public record Tints(int[] grass, int[] foliage, int[] dryFoliage, int[] water) {

        public Tints {
            grass = copyColumns(grass);
            foliage = copyColumns(foliage);
            dryFoliage = copyColumns(dryFoliage);
            water = copyColumns(water);
        }
    }

    public long key() {
        return key(this.chunkX, this.chunkZ);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
