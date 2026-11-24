package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;

import dev.diorama.Facing;

/** Scalar RGB oracle for the actual shared blend; no biome, game, GL or live-world classes. */
public final class TintBlendProbe {

    private static final int CHANNELS = 4;
    private static final MessageDigest HASH = digest();
    private static int cases;

    public static void main(String[] args) {
        int[][] origins = { { 0, 0 }, { -1, 2 }, { -4097, -129 } };
        for (int[] origin : origins) for (int radius : new int[] { 0, 1, 3, 7 }) {
            for (int terrain = 0; terrain < 2; terrain++) for (int mode = 0; mode < 5; mode++) {
                verify(origin[0], origin[1], radius, terrain, mode);
            }
        }
        rounding();
        minimumHeightSentinel();
        turns();
        StringBuilder hex = new StringBuilder();
        for (byte value : HASH.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS tint blends/unknown samples/fallback/height reuse/rotation; cases=" + cases
                + "; Minecraft biome zoom, resolvers and snapshot publication UNTESTED");
    }

    private static void verify(int chunkX, int chunkZ, int radius, int terrain, int mode) {
        Fixture source = new Fixture(chunkX, chunkZ, terrain, mode);
        int[] before = source.surface.clone();
        int[][] actual = TintBlend.blend(chunkX, chunkZ, radius, CHANNELS, source);
        require(actual.length == CHANNELS && Arrays.equals(before, source.surface), "input surface unchanged");
        int fallbackCount = 0;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = (chunkX << 4) + x;
            int wz = (chunkZ << 4) + z;
            int y = before[z * 16 + x];
            // Deliberately uncached scalar reference: long sums and per-channel division.
            long[][] sum = new long[CHANNELS][3];
            int known = 0;
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (!source.known(wx + dx, y, wz + dz)) continue;
                known++;
                for (int r = 0; r < CHANNELS; r++) {
                    int color = color(mode, r, wx + dx, y, wz + dz, false);
                    sum[r][0] += color >>> 16 & 255;
                    sum[r][1] += color >>> 8 & 255;
                    sum[r][2] += color & 255;
                }
            }
            if (known == 0) fallbackCount++;
            for (int r = 0; r < CHANNELS; r++) {
                int expected = known == 0 ? color(mode, r, wx, y, wz, true) & 0xFFFFFF
                    : (int) (sum[r][0] / known) * 65536 + (int) (sum[r][1] / known) * 256 + (int) (sum[r][2] / known);
                require(actual[r].length == 256 && actual[r][z * 16 + x] == expected, "RGB blend scalar oracle");
                require((actual[r][z * 16 + x] & 0xFF000000) == 0, "output alpha removed");
                put(actual[r][z * 16 + x]);
            }
        }
        require(source.fallbacks == fallbackCount && source.surfaceCalls == 256, "fallback and surface calls");
        if (terrain == 0) require(
            source.samples == (16 + 2 * radius) * (16 + 2 * radius),
            "flat-height cache includes unknown samples");
        if (terrain == 1 && radius > 0)
            require(source.samples > (16 + 2 * radius) * (16 + 2 * radius), "changed height resampled");
        if (mode == 2 && radius > 0)
            for (int color : actual[0]) require(color == 0, "known black counted, not replaced by fallback");
        int[][] second = TintBlend.blend(chunkX, chunkZ, radius, CHANNELS, new Fixture(chunkX, chunkZ, terrain, mode));
        require(
            Arrays.deepEquals(actual, second) && actual != second && actual[0] != second[0],
            "deterministic private result");
        cases++;
    }

    private static void rounding() {
        TintBlend.Source input = new TintBlend.Source() {

            @Override
            public int surface(int x, int z) {
                return -64;
            }

            @Override
            public boolean sample(int x, int y, int z, int[][] out, int cell) {
                for (int r = 0; r < CHANNELS; r++) out[r][cell] = x == 0 && z == 0 ? 0xFE010101 : 0x01000000;
                return true;
            }

            @Override
            public void fallback(int x, int y, int z, int[] out) {
                throw new AssertionError("unexpected fallback");
            }
        };
        int[][] result = TintBlend.blend(0, 0, 1, CHANNELS, input);
        for (int[] channel : result) require(channel[0] == 0, "integer division floors, alpha has no weight");
        cases++;
    }

    private static void minimumHeightSentinel() {
        // Preserve the existing cache-sentinel corner case, not a change to valid world-height rules.
        Fixture source = new Fixture(0, 0, 0, 0);
        Arrays.fill(source.surface, Integer.MIN_VALUE);
        int[][] result = TintBlend.blend(0, 0, 1, CHANNELS, source);
        require(source.samples == 0 && source.fallbacks == 256, "original MIN_VALUE sentinel behavior");
        for (int r = 0; r < CHANNELS; r++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) require(
            result[r][z * 16 + x] == (color(0, r, x, Integer.MIN_VALUE, z, true) & 0xFFFFFF),
            "sentinel fallback color");
        cases++;
    }

    private static void turns() {
        int[] original = new int[256];
        for (int i = 0; i < 256; i++) original[i] = 0x240000 | i;
        int[] before = original.clone();
        int[] identity = TintBlend.turn(original, null);
        require(Arrays.equals(original, identity) && identity != original, "identity view copies");
        for (Facing facing : Facing.values()) {
            int[] columns = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                columns[z * 16 + x] = ((facing.worldBlockZ(x, z) & 15) << 4) | (facing.worldBlockX(x, z) & 15);
            int[] mapBefore = columns.clone();
            int[] turned = TintBlend.turn(original, columns);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wx = x;
                int wz = z;
                for (int t = 0; t < facing.turns; t++) {
                    int oldX = wx;
                    wx = 15 - wz;
                    wz = oldX;
                }
                require(turned[z * 16 + x] == before[wz * 16 + wx], "independent inverse rotation oracle");
                put(turned[z * 16 + x]);
            }
            require(Arrays.equals(columns, mapBefore) && Arrays.equals(original, before), "turn inputs unchanged");
            turned[0] = -1;
            require(Arrays.equals(original, before), "turned output not alias");
            cases++;
        }
        int[] permutation = new int[256];
        for (int i = 0; i < 256; i++) permutation[i] = (i * 73 + 19) & 255;
        int[] permuted = TintBlend.turn(original, permutation);
        for (int i = 0; i < 256; i++) require(permuted[i] == original[permutation[i]], "arbitrary prepared map");
    }

    private static int color(int mode, int channel, int x, int y, int z, boolean fallback) {
        int r = (x * 17 + y * 3 + z * 7 + channel * 29) & 255;
        int g = (x * 5 - y * 7 + z * 23 + channel * 43) & 255;
        int b = (x * 31 + y * 11 - z * 13 + channel * 61) & 255;
        int alpha = (x + y + z + channel) & 255;
        if (fallback) return 0xF0000000 | ((r ^ 255) << 16) | ((g ^ 85) << 8) | (b ^ 170);
        if (mode == 1) return (alpha << 24) | (0x2070A0 + channel * 0x10101);
        if (mode == 2 && channel == 0) return alpha << 24; // Known black, with arbitrary alpha.
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static final class Fixture implements TintBlend.Source {

        private final int baseX;
        private final int baseZ;
        private final int mode;
        private final int[] surface = new int[256];
        private int samples;
        private int fallbacks;
        private int surfaceCalls;

        Fixture(int chunkX, int chunkZ, int terrain, int mode) {
            baseX = chunkX << 4;
            baseZ = chunkZ << 4;
            this.mode = mode;
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) surface[z * 16 + x] = terrain == 0 ? 64 : -64 + (x + z * 2) % 5;
        }

        @Override
        public int surface(int x, int z) {
            surfaceCalls++;
            return surface[z * 16 + x];
        }

        @Override
        public boolean sample(int x, int y, int z, int[][] out, int cell) {
            samples++;
            if (!known(x, y, z)) return false; // Keep stale cache values, which the shared known flag must exclude.
            for (int r = 0; r < CHANNELS; r++) out[r][cell] = color(mode, r, x, y, z, false);
            return true;
        }

        @Override
        public void fallback(int x, int y, int z, int[] out) {
            fallbacks++;
            for (int r = 0; r < CHANNELS; r++) out[r] = color(mode, r, x, y, z, true);
        }

        boolean known(int x, int y, int z) {
            if (mode == 1) return x >= baseX && x < baseX + 16 && z >= baseZ && z < baseZ + 16;
            if (mode == 2) return ((x ^ z) & 1) == 0;
            if (mode == 3) return false;
            if (mode == 4) return Math.floorMod(x + y + z, 3) != 0;
            return true;
        }
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
