package dev.diorama.gtnh.capture;

import org.jspecify.annotations.Nullable;

import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileKey;

/** One canonical surface overview step, without modern chunk, spot or block-light sidecars. */
public final class PreviewOverview {

    private static final int EDGE = Projection.TILE_PIXELS;

    private PreviewOverview() {}

    /** Index into build's child array. Negative tile coordinates use floor division. */
    public static int childIndex(TileKey child) {
        if (child.layer() != TileKey.SURFACE || child.lod() != 0)
            throw new IllegalArgumentException("Expected surface LOD0 child");
        return (child.tileV() & 1) * 2 + (child.tileU() & 1);
    }

    public static TileKey childKey(TileKey parent, int index) {
        requireParent(parent);
        if (index < 0 || index >= 4) throw new IllegalArgumentException("Invalid overview child index");
        return new TileKey(parent.layer(), parent.tileU() * 2 + (index & 1), parent.tileV() * 2 + (index >> 1));
    }

    /**
     * Returns a full-size LOD1 ABGR base. Children are ordered by childIndex, with null slots
     * left transparent. A child occupies one quadrant, filtered with the canonical mip kernel.
     */
    public static int[] build(TileKey parent, int @Nullable [][] children) {
        requireParent(parent);
        if (children.length != 4) throw new IllegalArgumentException("Expected four overview child slots");
        for (int[] child : children) if (child != null && child.length != EDGE * EDGE)
            throw new IllegalArgumentException("Invalid overview child raster length");

        int half = EDGE / 2;
        int[] out = new int[EDGE * EDGE];
        for (int index = 0; index < 4; index++) {
            int[] child = children[index];
            if (child == null) continue;
            int left = (index & 1) * half;
            int top = (index >> 1) * half;
            for (int y = 0; y < half; y++) {
                int src = y * 2 * EDGE;
                int dst = (top + y) * EDGE + left;
                for (int x = 0; x < half; x++, src += 2) out[dst + x] = Mipmaps
                    .average(child[src], child[src + 1], child[src + EDGE], child[src + EDGE + 1]);
            }
        }
        return out;
    }

    private static void requireParent(TileKey parent) {
        if (parent.layer() != TileKey.SURFACE || parent.lod() != 1
            || parent.tileU() < Integer.MIN_VALUE / 2
            || parent.tileU() > Integer.MAX_VALUE / 2
            || parent.tileV() < Integer.MIN_VALUE / 2
            || parent.tileV() > Integer.MAX_VALUE / 2)
            throw new IllegalArgumentException("Expected surface LOD1 parent with representable children");
    }
}
