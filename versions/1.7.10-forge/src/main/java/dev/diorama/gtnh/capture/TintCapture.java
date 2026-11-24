package dev.diorama.gtnh.capture;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.TintBlend;
import dev.diorama.data.ChunkSnapshot;

/** Client-thread preparation; the canonical blender sees only detached numeric inputs. */
public final class TintCapture {

    private TintCapture() {}

    public interface Source {

        boolean onThread();

        boolean current();

        Object session();

        /** Borrow already loaded biome columns; never load or request a neighbour. */
        int @Nullable [] biomes(int chunkX, int chunkZ);

        /** Borrow grass, foliage and water RGB (alpha ignored), or null while unavailable. */
        int @Nullable [] colors(int biomeId, int x, int y, int z);
    }

    public static int[] biomes(byte[] raw) {
        if (raw.length != 256) throw new IllegalArgumentException("Expected 256 biome columns");
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) {
            int id = raw[i] & 255;
            out[i] = id == 255 ? -1 : id;
        }
        return out;
    }

    public static int[] biomes(short[] raw) {
        if (raw.length != 256) throw new IllegalArgumentException("Expected 256 biome columns");
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) {
            int id = raw[i] & 65535;
            out[i] = id == 65535 ? -1 : id;
        }
        return out;
    }

    /** Null means pending/stale, not a white or plains substitute. Callback failures propagate. */
    public static ChunkSnapshot.@Nullable Tints prepare(Source source, Object token, int cx, int cz, int[] surface,
        int radius) {
        Objects.requireNonNull(source, "source");
        // No epoch callback or argument validation may precede the thread gate.
        if (!source.onThread()) throw new IllegalStateException("Tint capture requires the source thread");
        Objects.requireNonNull(token, "token");
        if (!valid(source, token)) return null;
        int[] heights = surface.clone();
        if (heights.length != 256) throw new IllegalArgumentException("Expected 256 surface columns");
        for (int i = 0; i < 256; i++) {
            int y = heights[i];
            if (y == Integer.MIN_VALUE) heights[i] = 0;
            else if (y < 0 || y > 255) throw new IllegalArgumentException("Surface outside 0..255");
        }
        if (radius < 0 || radius > 7) throw new IllegalArgumentException("Tint radius outside 0..7");
        int ox = origin(cx, radius), oz = origin(cz, radius);
        int size = 16 + radius * 2;
        int[] grid = new int[size * size];
        Arrays.fill(grid, -1);
        // Centre first: every output must have a known centre, even when its halo is known.
        if (!valid(source, token)) return null;
        int[] borrowed = source.biomes(cx, cz);
        int[] center = borrowed == null ? null : borrowed.clone();
        if (!valid(source, token)) return null;
        if (center == null) return null;
        checkBiomes(center);
        for (int id : center) if (id < 0) return null;
        for (int zc = (oz - radius) >> 4; zc <= (oz + 15 + radius) >> 4; zc++) {
            for (int xc = (ox - radius) >> 4; xc <= (ox + 15 + radius) >> 4; xc++) {
                int[] ids = center;
                if (xc != cx || zc != cz) {
                    if (!valid(source, token)) return null;
                    borrowed = source.biomes(xc, zc);
                    ids = borrowed == null ? null : borrowed.clone();
                    if (!valid(source, token)) return null;
                    if (ids == null) continue;
                    checkBiomes(ids);
                }
                for (int gz = 0; gz < size; gz++) for (int gx = 0; gx < size; gx++) {
                    int x = ox - radius + gx, z = oz - radius + gz;
                    if ((x >> 4) == xc && (z >> 4) == zc) grid[gz * size + gx] = ids[((z & 15) << 4) | (x & 15)];
                }
            }
        }
        Map<Long, int[]> samples = new HashMap<>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int y = heights[z * 16 + x];
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                int cell = (z + dz + radius) * size + x + dx + radius;
                if (grid[cell] < 0) continue;
                long key = key(y, cell);
                if (samples.containsKey(key)) continue;
                if (!valid(source, token)) return null;
                borrowed = source.colors(grid[cell], ox + x + dx, y, oz + z + dz);
                int[] rgb = borrowed == null ? null : borrowed.clone();
                if (!valid(source, token)) return null;
                if (rgb == null) return null;
                if (rgb.length != 3) throw new IllegalArgumentException("Expected three tint channels");
                samples.put(key, rgb);
            }
        }
        int[][] rgb = TintBlend.blend(cx, cz, radius, 3, new Prepared(heights, samples, ox, oz, radius));
        if (!valid(source, token)) return null;
        // GTNH has no separate dry-foliage resolver; keep the legacy slot compatible.
        return new ChunkSnapshot.Tints(rgb[0], rgb[1], rgb[1], rgb[2]);
    }

    private static boolean valid(Source source, Object token) {
        return source.current() && source.session() == token && source.current();
    }

    private static int origin(int chunk, int radius) {
        long origin = (long) chunk * 16;
        if (origin - radius < Integer.MIN_VALUE || origin + 15 + radius > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Tint halo outside integer coordinates");
        return (int) origin;
    }

    private static void checkBiomes(int[] ids) {
        if (ids.length != 256) throw new IllegalArgumentException("Expected 256 biome columns");
        for (int id : ids) if (id < -1 || id > 65534) throw new IllegalArgumentException("Biome outside -1..65534");
    }

    private static long key(int y, int cell) {
        return ((long) y << 32) | (cell & 0xffffffffL);
    }

    /** Deliberately static: no live Source, epoch token or resolver can enter the blender. */
    private static final class Prepared implements TintBlend.Source {

        private final int[] surface;
        private final Map<Long, int[]> samples;
        private final int ox, oz, radius, size;

        private Prepared(int[] surface, Map<Long, int[]> samples, int ox, int oz, int radius) {
            this.surface = surface;
            this.samples = samples;
            this.ox = ox;
            this.oz = oz;
            this.radius = radius;
            this.size = 16 + radius * 2;
        }

        public int surface(int x, int z) {
            return surface[z * 16 + x];
        }

        public boolean sample(int x, int y, int z, int[][] out, int cell) {
            int[] rgb = samples.get(key(y, (z - oz + radius) * size + x - ox + radius));
            if (rgb == null) return false;
            for (int channel = 0; channel < 3; channel++) out[channel][cell] = rgb[channel];
            return true;
        }

        public void fallback(int x, int y, int z, int[] out) {
            throw new IllegalStateException("Known centre must be sampled");
        }
    }
}
