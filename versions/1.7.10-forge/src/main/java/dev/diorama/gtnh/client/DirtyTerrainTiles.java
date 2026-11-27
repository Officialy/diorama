package dev.diorama.gtnh.client;

import java.util.BitSet;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;

/** Projects world changes onto the canonical 32px patches of one GTNH surface tile. */
final class DirtyTerrainTiles {

    private static final int LAST_Y = 255;
    private static final int LIGHT_REACH = 15;
    private static final int AROUND = Math.max(LIGHT_REACH, TileBaker.SKY_REACH);
    private static final int EAST = Math.max(AROUND, TileBaker.SHADOW_REACH_X);
    private static final int NORTH = Math.max(AROUND, TileBaker.SHADOW_REACH_Z);
    private static final int DOWN = Math.max(AROUND, TileBaker.SHADOW_REACH_Y);
    private static final double OUTLINE_HALO = 1.0 / Projection.PIXELS_PER_UNIT;
    private static final double PATCH_UNITS = (double) TileImage.BLOCK / Projection.PIXELS_PER_UNIT;
    private static final int PATCHES_PER_ROW = Projection.TILE_PIXELS / TileImage.BLOCK;
    // The preview only bakes the default north-facing projection. No mutable style/facing reads.
    private static final double PITCH = Math.toRadians(MapStyle.DEFAULT.pitchDegrees());
    private static final double SIN = Math.sin(PITCH);
    private static final double COS = Math.cos(PITCH);

    private DirtyTerrainTiles() {}

    static BitSet block(TileKey tile, int x, int y, int z) {
        return project(tile, x, y, z, x, y, z);
    }

    static BitSet section(TileKey tile, int chunkX, int sectionY, int chunkZ) {
        long x = (long) chunkX * 16, y = (long) sectionY * 16, z = (long) chunkZ * 16;
        return project(tile, x, y, z, x + 15, y + 15, z + 15);
    }

    static BitSet chunk(TileKey tile, int chunkX, int chunkZ) {
        long x = (long) chunkX * 16, z = (long) chunkZ * 16;
        // When availability changes, rays at any world height may have stopped at the chunk.
        return project(tile, x, 0, z, x + 15, LAST_Y, z + 15);
    }

    /** Inclusive world block box; use a wider box when an event covers more than one block. */
    static BitSet range(TileKey tile, int x0, int y0, int z0, int x1, int y1, int z1) {
        return project(tile, x0, y0, z0, x1, y1, z1);
    }

    /** Conservative ray and shading reach for selecting detached chunk snapshots, including caves. */
    static boolean intersectsChunk(TileKey tile, int cx, int cz) {
        long x = (long) cx * 16, z = (long) cz * 16;
        double u0 = x - 1 - AROUND - OUTLINE_HALO;
        double u1 = x + 17 + EAST + OUTLINE_HALO;
        double v0 = (z - 1 - NORTH) * SIN - (LAST_Y + 2 + LIGHT_REACH) * COS - OUTLINE_HALO;
        double v1 = (z + 17 + AROUND) * SIN + (1 + DOWN) * COS + OUTLINE_HALO;
        return u0 < tile.minU() + tile.units() && u1 >= tile.minU()
            && v0 < tile.minV() + tile.units()
            && v1 >= tile.minV();
    }

    /** Conservative inverse projection, inclusive chunk bounds: minX,minZ,maxX,maxZ.
     * A one-chunk rounding margin is filtered by intersectsChunk at the caller. */
    static int[] chunkBounds(TileKey tile) {
        double x0 = tile.minU() - 17 - EAST - OUTLINE_HALO;
        double x1 = tile.minU() + tile.units() + 1 + AROUND + OUTLINE_HALO;
        double z0 = (tile.minV() - (1 + DOWN) * COS - OUTLINE_HALO) / SIN - 17 - AROUND;
        double z1 = (tile.minV() + tile.units() + (LAST_Y + 2 + LIGHT_REACH) * COS + OUTLINE_HALO) / SIN
            + 1 + NORTH;
        return new int[] { (int) Math.floor(x0 / 16) - 1, (int) Math.floor(z0 / 16) - 1,
            (int) Math.floor(x1 / 16) + 1, (int) Math.floor(z1 / 16) + 1 };
    }

    private static BitSet project(TileKey tile, long x0, long y0, long z0, long x1, long y1, long z1) {
        BitSet mask = new BitSet();
        if (tile.isCave() || tile.lod() != 0 || x0 > x1 || y0 > y1 || z0 > z1 || y1 < 0 || y0 > LAST_Y) return mask;

        // MapLevel's surface dirty box: face/AO padding, sky occlusion and directional shadows.
        // Also allow the vanilla block-light radius: legacy notifications don't separate light from edits.
        double top = Math.min(y1, LAST_Y) + 2 + LIGHT_REACH;
        double bottom = Math.max(y0, 0) - 1 - DOWN;
        double u0 = x0 - 1 - AROUND - OUTLINE_HALO;
        double u1 = x1 + 2 + EAST + OUTLINE_HALO;
        double v0 = (z0 - 1 - NORTH) * SIN - top * COS - OUTLINE_HALO;
        double v1 = (z1 + 2 + AROUND) * SIN - bottom * COS + OUTLINE_HALO;

        // A patch's lower edge is included, its upper edge excluded; floor even at negative seams.
        int col0 = firstPatch(u0, tile.minU()), col1 = lastPatch(u1, tile.minU());
        int row0 = firstPatch(v0, tile.minV()), row1 = lastPatch(v1, tile.minV());
        if (col0 > col1 || row0 > row1) return mask;
        for (int row = row0; row <= row1; row++) {
            mask.set(row * PATCHES_PER_ROW + col0, row * PATCHES_PER_ROW + col1 + 1);
        }
        return mask;
    }

    private static int firstPatch(double min, double origin) {
        return Math.max(0, (int) Math.floor((min - origin) / PATCH_UNITS));
    }

    private static int lastPatch(double max, double origin) {
        return Math.min(PATCHES_PER_ROW - 1, (int) Math.floor((max - origin) / PATCH_UNITS));
    }
}
