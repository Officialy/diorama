package dev.diorama.gtnh.probe;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.TintCapture;

/** Game-free adapter tests; the averaging oracle is independent scalar/long arithmetic. */
public final class TintCaptureProbe {

    private static final MessageDigest HASH = digest();
    private static int assertions;

    public static void main(String[] args) throws InterruptedException {
        raw();
        blends();
        guards();
        pendingAndInvalid();
        StringBuilder hex = new StringBuilder();
        for (byte b : HASH.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS tint-capture: " + assertions
                + " assertions; unsigned exhaustive, scalar/long oracle, heights, halo, ownership, epoch, reuse; native hooks/game UNTESTED");
    }

    private static void raw() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < 256; i++) bytes[i] = (byte) i;
        int[] decoded = TintCapture.biomes(bytes);
        Arrays.fill(bytes, (byte) 0);
        for (int i = 0; i < 256; i++) eq(decoded[i], i == 255 ? -1 : i, "unsigned byte");
        short[] shorts = new short[256];
        for (int base = 0; base < 65536; base += 256) {
            for (int i = 0; i < 256; i++) shorts[i] = (short) (base + i);
            decoded = TintCapture.biomes(shorts);
            Arrays.fill(shorts, (short) 0);
            for (int i = 0; i < 256; i++) {
                int id = base + i;
                eq(decoded[i], id == 65535 ? -1 : id, "unsigned short, including valid 255");
            }
        }
        for (int n : new int[] { 0, 255, 257 }) {
            expect(IllegalArgumentException.class, () -> TintCapture.biomes(new byte[n]));
            expect(IllegalArgumentException.class, () -> TintCapture.biomes(new short[n]));
        }
        expect(NullPointerException.class, () -> TintCapture.biomes((byte[]) null));
        expect(NullPointerException.class, () -> TintCapture.biomes((short[]) null));
    }

    private static void blends() {
        for (int radius : new int[] { 0, 1, 7 }) for (int mode = 0; mode < 4; mode++) {
            Fixture f = new Fixture(-3, -5);
            f.halo = mode;
            int[] heights = heights();
            int[] original = heights.clone();
            f.mutateSurface = heights;
            f.poisonOnGuard = true;
            ChunkSnapshot.Tints result = TintCapture.prepare(f, f.token, f.cx, f.cz, heights, radius);
            check(result != null, "ready numeric fixture");
            verify(result, f, original, radius);
            check(f.colorCalls == f.keys.size(), "one callback per (y,gridcell)");
            check(f.colorCalls <= 256 * (2 * radius + 1) * (2 * radius + 1), "callback bound");
            check(f.biomeCalls == (radius == 0 ? 1 : 9), "only needed chunks, once each");
            Arrays.fill(f.ids, -1);
            Arrays.fill(f.rgb, -1);
            Arrays.fill(heights, -999);
            verify(result, f, original, radius);
        }
        for (int cx : new int[] { -134217728, 134217727 }) {
            Fixture f = new Fixture(cx, cx);
            int[] heights = new int[256];
            ChunkSnapshot.Tints result = TintCapture.prepare(f, f.token, cx, cx, heights, 0);
            check(result != null, "inclusive integer coordinate boundary");
            verify(result, f, heights, 0);
        }
        Fixture black = new Fixture(0, 0);
        black.black = true;
        ChunkSnapshot.Tints result = TintCapture.prepare(black, black.token, 0, 0, new int[256], 7);
        check(result != null, "black is known, not pending");
        verify(result, black, new int[256], 7);
        Fixture flat = new Fixture(0, 0);
        check(TintCapture.prepare(flat, flat.token, 0, 0, new int[256], 7) != null, "flat ready");
        eq(flat.colorCalls, 30 * 30, "flat overlap deduplicated globally");
    }

    private static void verify(ChunkSnapshot.Tints actual, Fixture f, int[] heights, int radius) {
        int[][] channels = { actual.grass(), actual.foliage(), actual.water() };
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int i = z * 16 + x;
            int y = heights[i] == Integer.MIN_VALUE ? 0 : heights[i];
            long[][] sums = new long[3][3];
            int count = 0;
            for (long wz = (long) f.cz * 16 + z - radius; wz <= (long) f.cz * 16 + z + radius; wz++) {
                for (long wx = (long) f.cx * 16 + x - radius; wx <= (long) f.cx * 16 + x + radius; wx++) {
                    int biome = f.biome((int) wx, (int) wz);
                    if (biome < 0) continue;
                    count++;
                    for (int channel = 0; channel < 3; channel++) {
                        int rgb = f.color(biome, (int) wx, y, (int) wz, channel);
                        sums[channel][0] += (rgb >>> 16) & 255;
                        sums[channel][1] += (rgb >>> 8) & 255;
                        sums[channel][2] += rgb & 255;
                    }
                }
            }
            check(count > 0, "centre guarantees known sample");
            for (int c = 0; c < 3; c++) {
                int expected = (int) ((sums[c][0] / count) * 65536 + (sums[c][1] / count) * 256 + sums[c][2] / count);
                eq(channels[c][i], expected, "independent long average");
            }
            eq(actual.dryFoliage()[i], actual.foliage()[i], "legacy dry foliage slot");
        }
    }

    private static void guards() throws InterruptedException {
        Fixture f = new Fixture(0, 0);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                TintCapture.prepare(f, null, Integer.MAX_VALUE, 0, null, -1);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        worker.start();
        worker.join();
        check(error.get() instanceof IllegalStateException, "thread gate precedes invalid arguments");
        eq(f.epochCalls, 0, "no epoch callback off owner thread");
        eq(f.liveCalls, 0, "no live callback off owner thread");
        Fixture baseline = new Fixture(0, 0);
        check(TintCapture.prepare(baseline, baseline.token, 0, 0, new int[256], 1) != null, "guard baseline");
        for (boolean switchToken : new boolean[] { false, true }) {
            for (int stop = 1; stop <= baseline.liveCalls; stop++) {
                Fixture stopped = new Fixture(0, 0);
                stopped.stop = stop;
                stopped.switchToken = switchToken;
                Object token = stopped.token;
                check(
                    TintCapture.prepare(stopped, token, 0, 0, new int[256], 1) == null,
                    "each live callback may invalidate capture");
                eq(stopped.liveCalls, stop, "no callback after invalidation");
            }
        }
        Fixture stale = new Fixture(0, 0);
        stale.active = false;
        check(TintCapture.prepare(stale, stale.token, 0, 0, null, -1) == null, "stale before payload validation");
        eq(stale.liveCalls, 0, "no stale live callbacks");
        Fixture revoked = new Fixture(0, 0);
        revoked.revokeInSession = true;
        check(
            TintCapture.prepare(revoked, revoked.token, 0, 0, new int[256], 0) == null,
            "session callback revokes current while returning old token");
        eq(revoked.liveCalls, 0, "no live callback after session revocation");
        Fixture changed = new Fixture(0, 0);
        check(TintCapture.prepare(changed, new Object(), 0, 0, new int[256], 0) == null, "token identity");
        eq(changed.liveCalls, 0, "no mismatched live callbacks");
    }

    private static void pendingAndInvalid() {
        for (int mode = 1; mode <= 3; mode++) {
            Fixture f = new Fixture(0, 0);
            f.pending = mode;
            check(TintCapture.prepare(f, f.token, 0, 0, new int[256], 1) == null, "pending centre/color");
            eq(f.colorCalls, mode == 3 ? 1 : 0, "pending stops immediately");
        }
        for (int bad : new int[] { -2, 65535, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            Fixture f = new Fixture(0, 0);
            f.badBiome = bad;
            expect(IllegalArgumentException.class, () -> TintCapture.prepare(f, f.token, 0, 0, new int[256], 1));
        }
        for (int n : new int[] { 0, 255, 257 }) {
            Fixture f = new Fixture(0, 0);
            expect(IllegalArgumentException.class, () -> TintCapture.prepare(f, f.token, 0, 0, new int[n], 0));
            f.badLength = n;
            expect(IllegalArgumentException.class, () -> TintCapture.prepare(f, f.token, 0, 0, new int[256], 0));
        }
        for (int n : new int[] { 0, 2, 4 }) {
            Fixture f = new Fixture(0, 0);
            f.colorLength = n;
            expect(IllegalArgumentException.class, () -> TintCapture.prepare(f, f.token, 0, 0, new int[256], 0));
        }
        for (int bad : new int[] { -1, 256, Integer.MAX_VALUE }) {
            Fixture f = new Fixture(0, 0);
            int[] heights = new int[256];
            heights[255] = bad;
            expect(IllegalArgumentException.class, () -> TintCapture.prepare(f, f.token, 0, 0, heights, 0));
            eq(f.liveCalls, 0, "height validation before live callbacks");
        }
        for (int radius : new int[] { -1, 8, Integer.MAX_VALUE }) {
            Fixture f = new Fixture(0, 0);
            expect(IllegalArgumentException.class, () -> TintCapture.prepare(f, f.token, 0, 0, new int[256], radius));
            eq(f.liveCalls, 0, "radius validation before live callbacks");
        }
        for (int chunk : new int[] { Integer.MIN_VALUE, -134217729, 134217728, Integer.MAX_VALUE, -134217728,
            134217727 }) for (boolean x : new boolean[] { false, true }) {
                Fixture f = new Fixture(0, 0);
                expect(
                    IllegalArgumentException.class,
                    () -> TintCapture.prepare(f, f.token, x ? chunk : 0, x ? 0 : chunk, new int[256], 1));
                eq(f.liveCalls, 0, "coordinate and halo overflow rejected before live callbacks");
            }
        for (int fail : new int[] { 1, 2, 3, 4 }) {
            Fixture f = new Fixture(0, 0);
            f.fail = fail;
            expect(UnsupportedOperationException.class, () -> TintCapture.prepare(f, f.token, 0, 0, new int[256], 0));
        }
    }

    private static int[] heights() {
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) out[i] = i % 17 == 0 ? Integer.MIN_VALUE : (i * 53) & 255;
        return out;
    }

    private static final class Fixture implements TintCapture.Source {

        final Thread owner = Thread.currentThread();
        final int cx, cz;
        final int[] ids = new int[256], rgb = new int[3];
        final Set<String> keys = new HashSet<>();
        Object token = new Object();
        boolean active = true, switchToken, poisonOnGuard, black, revokeInSession;
        int halo, pending, liveCalls, biomeCalls, colorCalls, epochCalls, stop = -1;
        int badBiome = -1, badLength = 256, colorLength = 3, fail;
        int[] mutateSurface;

        Fixture(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        public boolean onThread() {
            return Thread.currentThread() == owner;
        }

        public boolean current() {
            epochCalls++;
            if (fail == 1) throw new UnsupportedOperationException("current");
            if (poisonOnGuard) {
                Arrays.fill(ids, -999);
                Arrays.fill(rgb, -999);
            }
            return active;
        }

        public Object session() {
            epochCalls++;
            if (fail == 2) throw new UnsupportedOperationException("session");
            if (revokeInSession) active = false;
            return token;
        }

        private void live() {
            check(onThread(), "resolver on owner thread");
            check(active, "never live after stale");
            liveCalls++;
            if (mutateSurface != null) Arrays.fill(mutateSurface, -999);
            if (liveCalls == stop) {
                if (switchToken) token = new Object();
                else active = false;
            }
        }

        public int[] biomes(int x, int z) {
            live();
            biomeCalls++;
            if (fail == 3) throw new UnsupportedOperationException("biomes");
            if (pending == 1 && x == cx && z == cz) return null;
            if (halo == 1 && (x != cx || z != cz)) return null;
            for (int i = 0; i < 256; i++) ids[i] = biome(x * 16 + (i & 15), z * 16 + (i >> 4));
            if (pending == 2 && x == cx && z == cz) ids[255] = -1;
            if (badBiome != -1) ids[255] = badBiome;
            return badLength == 256 ? ids : new int[badLength];
        }

        int biome(int x, int z) {
            boolean center = (x >> 4) == cx && (z >> 4) == cz;
            if (!center && (halo == 1 || halo == 2 || (halo == 3 && ((x ^ z) & 3) == 0))) return -1;
            return (int) Math.floorMod((long) x * 37 + (long) z * 101, 65535L);
        }

        public int[] colors(int biome, int x, int y, int z) {
            live();
            colorCalls++;
            if (fail == 4) throw new UnsupportedOperationException("colors");
            check(keys.add(x + ":" + y + ":" + z), "global y/cell dedup");
            if (pending == 3) return null;
            for (int c = 0; c < 3; c++) rgb[c] = color(biome, x, y, z, c);
            return colorLength == 3 ? rgb : new int[colorLength];
        }

        int color(int biome, int x, int y, int z, int channel) {
            if (black) return 0xff000000;
            long n = (long) biome * 65537 + (long) x * 7919 + (long) z * 104729 + y * 313L + channel * 999983L;
            return 0xa5000000 | (int) (n & 0xffffff);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            check(type.isInstance(t), "expected " + type + ", got " + t);
            return;
        }
        throw new AssertionError("Expected " + type);
    }

    private static void eq(int actual, int expected, String message) {
        check(actual == expected, message + ": " + actual + " != " + expected);
        HASH.update((byte) (actual >>> 24));
        HASH.update((byte) (actual >>> 16));
        HASH.update((byte) (actual >>> 8));
        HASH.update((byte) actual);
    }

    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
