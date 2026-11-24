package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;

import dev.diorama.MapStyle;
import dev.diorama.Projection;

/** Numeric utility checks: scalar row enumeration, known sun angles and an independent 48-bit LCG. */
public final class BakeUtilityProbe {

    private static final MessageDigest HASH = digest();
    private static final int COLUMNS = 128;
    private static final int ROWS = 512;
    private static final int SPOT = 2 << 24;
    private static int cases;

    public static void main(String[] args) {
        MapStyle previous = MapStyle.get();
        try {
            sunlight();
            rowMiddles();
            savedRays();
            slimes();
        } finally {
            MapStyle.set(previous);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : HASH.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS CPU utilities; cases=" + cases
                + "; saved-row queries, sun and legacy slime RNG only; modern RNG binding/capture/GL UNTESTED");
    }

    private static void sunlight() {
        double rootHalf = Math.sqrt(0.5);
        double rootThreeHalf = Math.sqrt(3) / 2;
        double[][] angles = { { 0, 1, 0 }, { 30, rootThreeHalf, 0.5 }, { 45, rootHalf, rootHalf },
            { 60, 0.5, rootThreeHalf }, { 90, 0, 1 } };
        for (double[] a : angles) {
            MapStyle style = style(a[0]);
            double[] sun = SunDirection.sun(style);
            require(sun.length == 3, "sun component count");
            require(
                close(sun[0], -0.878 * a[1]) && close(sun[1], a[2]) && close(sun[2], 0.479 * a[1]),
                "known-angle sun components");
            double length = Math.sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]);
            require(
                Double.isFinite(length) && length >= 1 - 1e-15 && length <= 1.0002,
                "original approximately-unit azimuth preserved, not renormalized");
            double[] fresh = SunDirection.sun(style);
            sun[0] = 99;
            require(fresh[0] != 99, "sun returns independent vector");
            Lighting light = new Lighting(style);
            require(
                light.sunX == fresh[0] && light.sunY == fresh[1] && light.sunZ == fresh[2],
                "Lighting uses same shared direction");
            for (double component : fresh) put((int) Math.round(component * 1_000_000_000));
            cases++;
        }
        for (MapStyle style : MapStyle.VARIANTS) {
            double[] sun = SunDirection.sun(style);
            require(
                Double.isFinite(sun[0]) && Double.isFinite(sun[1])
                    && Double.isFinite(sun[2])
                    && sun[0] < 0
                    && sun[1] > 0
                    && sun[2] > 0,
                "current style finite sun direction signs");
            cases++;
        }
    }

    private static MapStyle style(double angle) {
        return new MapStyle(
            "Utility fixture",
            50,
            1,
            1,
            new float[] { 1, 1, 1 },
            new float[] { 1, 1, 1 },
            0,
            0,
            false,
            false,
            0,
            angle,
            0.5F,
            0.5F,
            0,
            0);
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 2e-15;
    }

    private static void rowMiddles() {
        double[][] values = { { -1024.375, -1024.375 }, { -0.25, -0.125 }, { -0.125, -0.125 }, { -0.0, 0.125 },
            { 0.124, 0.125 }, { 0.25, 0.375 }, { 4096.999, 4096.875 } };
        for (double[] value : values) {
            require(TileSpotQueries.rowMiddle(value[0]) == value[1], "negative and exact pixel-row middle");
            put((int) Math.round(value[1] * 8));
            cases++;
        }
    }

    private static void savedRays() {
        require(
            Projection.TILE_UNITS == COLUMNS && Projection.TILE_PIXELS == ROWS && Projection.PIXELS_PER_UNIT == 4,
            "saved spot format dimensions");
        int[] saved = new int[COLUMNS * ROWS];
        int trueCases = 0;
        int falseCases = 0;
        for (double pitch : new double[] { 25, 50, 60 }) {
            MapStyle.set(MapStyle.DEFAULT.withPitch(pitch));
            for (int[] tile : new int[][] { { -1, -1 }, { 0, 0 }, { 1, 0 } }) {
                TileKey key = new TileKey(TileKey.SURFACE, tile[0], tile[1]);
                int x0 = tile[0] * COLUMNS;
                for (int y : new int[] { -32, -1, 0, 40 }) {
                    int middleZ = (int) Math.floor((key.minV() + 64 + y * Projection.cos()) / Projection.sin());
                    for (int x : new int[] { x0 - 1, x0, x0 + 63, x0 + 127, x0 + 128 }) {
                        for (int z : new int[] { -10000, middleZ - 1, middleZ, middleZ + 1, 10000 }) {
                            for (int mode = 0; mode < 6; mode++) {
                                int value = mode == 1 ? 0
                                    : mode == 2 ? 1 << 24
                                        : mode == 3 ? encoded(-64, 255) : mode == 4 ? encoded(y, 0) : 3 << 24;
                                Arrays.fill(saved, value);
                                int[] input = mode == 0 ? null : saved;
                                boolean expected = savedOracle(key, input, x, y, z);
                                boolean actual = TileSpotQueries.passedWhenSaved(key, input, x, y, z);
                                require(actual == expected, "scalar saved-ray row oracle");
                                if (actual) trueCases++;
                                else falseCases++;
                                put(actual ? 1 : 0);
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        require(trueCases > 0 && falseCases > 0, "saved oracle includes both outcomes");
        MapStyle.set(MapStyle.DEFAULT.withPitch(50));
        TileKey key = new TileKey(TileKey.SURFACE, 0, 0);
        Arrays.fill(saved, encoded(-1, 123));
        require(TileSpotQueries.passedWhenSaved(key, saved, 3, 0, 100), "all saved rows agree");
        int disagreements = 0;
        double low = 100 * Projection.sin() - Projection.cos();
        double high = 101 * Projection.sin();
        for (int row = 0; row < ROWS; row++) {
            if ((row + 1) / 4.0 <= low || row / 4.0 >= high) continue;
            int index = row * COLUMNS + 3;
            int previous = saved[index];
            saved[index] = 0;
            require(
                !savedOracle(key, saved, 3, 0, 100) && !TileSpotQueries.passedWhenSaved(key, saved, 3, 0, 100),
                "one dissenting overlap row denies saved openness");
            saved[index] = previous;
            disagreements++;
            cases++;
        }
        require(disagreements >= 2, "multiple crossing/edge rows exercised");
        require(
            Arrays.stream(saved)
                .allMatch(value -> value == encoded(-1, 123)),
            "query never mutates saved spots");
    }

    private static int encoded(int y, int back) {
        return SPOT | back << 16 | (y & 65535);
    }

    private static boolean savedOracle(TileKey key, int[] saved, int x, int y, int z) {
        if (saved == null) return false;
        long column = (long) x - (long) key.minU();
        if (column < 0 || column >= COLUMNS) return false;
        // Enumerate all saved pixel-row intervals, rather than marching from a rounded row.
        double low = Math.max(key.minV(), z * Projection.sin() - (y + 1) * Projection.cos());
        double high = Math.min(key.minV() + 128, (z + 1) * Projection.sin() - y * Projection.cos());
        boolean centreCrossed = false;
        for (int row = 0; row < ROWS; row++) {
            double bottom = key.minV() + row / 4.0;
            double top = key.minV() + (row + 1) / 4.0;
            if (top <= low || bottom >= high || low >= high) continue;
            int value = saved[row * COLUMNS + (int) column];
            if ((value >>> 24) != 2 || y <= (short) value) return false;
            double middle = (bottom + top) / 2;
            if (middle >= low && middle < high) centreCrossed = true;
        }
        return centreCrossed;
    }

    private static void slimes() {
        long[] seeds = { 0, 1, -1, 987234911L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789ABCDEFL };
        int[] coordinates = { Integer.MIN_VALUE, -1000000, -46341, -1025, -1, 0, 1, 1024, 46341, 1000000,
            Integer.MAX_VALUE };
        int matches = 0;
        int misses = 0;
        for (long seed : seeds) for (int x : coordinates) for (int z : coordinates) {
            int roll = slimeRoll(seed, x, z);
            boolean expected = roll == 0;
            require(
                SlimeChunks.slimeChunk(seed, x, z) == expected && Overlays.slimeChunk(seed, x, z) == expected,
                "legacy slime rule against independent LCG and signed32 products");
            if (expected) matches++;
            else misses++;
            put(roll);
            cases++;
        }
        require(matches > 0 && misses > 0, "slime corpus contains slime and ordinary chunks");
        require(
            Overlays.CHUNK_WEST == 1 && Overlays.CHUNK_NORTH == 2
                && Overlays.SLIME == 4
                && Overlays.SPAWN_AT_NIGHT == 8
                && Overlays.SPAWN_ANY_TIME == 16
                && Overlays.DAYLIGHT_SPAWN_LIMIT == 7,
            "overlay bits and spawn threshold unchanged");
        Overlays overlays = new Overlays(null, true);
        require(overlays.slimeSeed() == null && overlays.spawnSpots(), "overlay nullable seed and record payload");
    }

    private static int slimeRoll(long seed, int x, int z) {
        // Vanilla Chunk arithmetic, evaluated with explicit signed32 narrowing rather than int products.
        long xx = signed32((long) x * x);
        long zz = signed32((long) z * z);
        long mixed = seed + signed32(xx * 4987142L)
            + signed32((long) x * 5947611L)
            + zz * 4392871L
            + signed32((long) z * 389711L);
        long state = ((mixed ^ 987234911L) ^ 0x5DEECE66DL) & ((1L << 48) - 1);
        for (;;) {
            state = (state * 0x5DEECE66DL + 11) & ((1L << 48) - 1);
            int bits = (int) (state >>> 17);
            int value = bits % 10;
            if ((long) bits - value + 9 < (1L << 31)) return value;
        }
    }

    private static long signed32(long value) {
        long unsigned = value & 0xFFFFFFFFL;
        return unsigned < (1L << 31) ? unsigned : unsigned - (1L << 32);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void put(int value) {
        HASH.update((byte) (value >>> 24));
        HASH.update((byte) (value >>> 16));
        HASH.update((byte) (value >>> 8));
        HASH.update((byte) value);
    }
}
