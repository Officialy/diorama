package dev.diorama.bake;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.GrassContext;

/** Numeric grass/context fixtures, not execution of Minecraft storage, registry or GL hooks. */
public final class GrassProbe {

    private static final MessageDigest HASH = digest();
    private static int assertions;
    private static final float[] TOP = { .5F, .25F, 1, 1 };
    private static final float[] SIDE = { 1, .5F, 0, 1 };
    private static final float[] SNOW_SIDE = { .25F, .5F, 1, 1 };
    private static final int SNOW = 0xFFFFFE, LAYER = 0xFFFFFD, GRASS = 90000;

    public static void main(String[] args) throws InterruptedException {
        contexts();
        guards();
        payload();
        invalid();
        captureAndBaker();
        StringBuilder hex = new StringBuilder();
        for (byte b : HASH.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS grass: " + assertions
                + " assertions; explicit context keys, raw context boundaries, pending/epochs, canonical faces, real ChunkCapture and TileBaker; native storage/registry/resource/game UNTESTED");
    }

    private static void contexts() {
        Scene s = new Scene();
        for (int y = 0; y < 255; y++) {
            for (int above : new int[] { 0, 1, SNOW, LAYER, 42 }) {
                s.clear();
                s.set(3, y + 1, 5, above);
                int expected = above == SNOW || above == LAYER ? GrassContext.SNOWY
                    : above == 42 ? GrassContext.UNKNOWN : GrassContext.NORMAL;
                eq(
                    GrassContext.resolve(s, s.token, 3, y, 5),
                    expected,
                    "raw above classification including all section boundaries");
                eq(s.readY, y + 1, "context reads y+1 without metadata truncation");
                eq(s.readX, 3, "x unchanged");
                eq(s.readZ, 5, "z unchanged");
            }
        }
        s.clear();
        eq(
            GrassContext.resolve(s, s.token, Integer.MIN_VALUE, 255, Integer.MAX_VALUE),
            GrassContext.NORMAL,
            "top boundary known normal");
        eq(s.reads, 0, "top boundary never wraps or queries a chunk");
        eq(s.classifications, 0, "top boundary never queries registry");
        eq(
            GrassContext.resolve(s, s.token, 3, 15, 5),
            GrassContext.NORMAL,
            "null upper section is known air, not UNKNOWN");
        s.set(-17, 16, -33, SNOW);
        eq(
            GrassContext.resolve(s, s.token, -17, 15, -33),
            GrassContext.SNOWY,
            "negative coordinates retain cross-section raw context");
        eq(s.readX, -17, "negative x not normalized before callback");
        eq(s.readZ, -33, "negative z not normalized before callback");
        s.contextMissing = true;
        eq(GrassContext.resolve(s, s.token, 3, 15, 5), GrassContext.UNKNOWN, "missing source is not air");
        s.contextMissing = false;
        s.set(3, 16, 5, SNOW);
        eq(GrassContext.resolve(s, s.token, 3, 15, 5), GrassContext.SNOWY, "same coordinate changes to snowy");
        s.set(3, 16, 5, 1);
        eq(
            GrassContext.resolve(s, s.token, 3, 15, 5),
            GrassContext.NORMAL,
            "same coordinate changes back without cached context");
        s.set(3, 16, 5, 42);
        eq(
            GrassContext.resolve(s, s.token, 3, 15, 5),
            GrassContext.UNKNOWN,
            "unregistered high-level lookup cannot become air");
    }

    private static void guards() throws InterruptedException {
        for (int mutation = 0; mutation < 4; mutation++) {
            Scene s = new Scene();
            s.mutation = mutation + 1;
            eq(
                GrassContext.resolve(s, s.token, 0, 15, 0),
                GrassContext.UNKNOWN,
                "callback epoch changes revoke context");
            if (mutation < 2) eq(s.classifications, 0, "no registry callback after raw read revoked epoch");
        }
        Scene stale = new Scene();
        stale.active = false;
        eq(GrassContext.resolve(stale, stale.token, 0, 15, 0), GrassContext.UNKNOWN, "initial stale");
        eq(stale.reads, 0, "stale has no raw read");
        Scene foreign = new Scene();
        eq(GrassContext.resolve(foreign, new Object(), 0, 15, 0), GrassContext.UNKNOWN, "foreign token");
        eq(foreign.reads, 0, "foreign token has no raw read");
        Scene reentrant = new Scene();
        reentrant.revokeInSession = true;
        eq(
            GrassContext.resolve(reentrant, reentrant.token, 0, 15, 0),
            GrassContext.UNKNOWN,
            "session callback returning old token cannot bypass current guard");
        eq(reentrant.reads, 0, "reentrant session revocation stops raw read");
        Scene wrong = new Scene();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                GrassContext.resolve(wrong, null, 0, -1, 0);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        worker.start();
        worker.join();
        require(error.get() instanceof IllegalStateException, "thread gate precedes invalid token/height");
        eq(wrong.checks, 0, "wrong thread has no current/session callbacks");
        eq(wrong.reads, 0, "wrong thread has no raw callback");
        expect(IllegalArgumentException.class, () -> GrassContext.resolve(new Scene(), new Object(), 0, -1, 0));
        expect(IllegalArgumentException.class, () -> GrassContext.resolve(new Scene(), new Object(), 0, 256, 0));
        Scene badRaw = new Scene();
        badRaw.rawOverride = -2;
        expect(IllegalArgumentException.class, () -> GrassContext.resolve(badRaw, badRaw.token, 0, 15, 0));
        Scene badKind = new Scene();
        badKind.kindOverride = 0;
        expect(IllegalArgumentException.class, () -> GrassContext.resolve(badKind, badKind.token, 0, 15, 0));
        Scene throwing = new Scene();
        throwing.fail = true;
        expect(UnsupportedOperationException.class, () -> GrassContext.resolve(throwing, throwing.token, 0, 15, 0));
    }

    private static void payload() throws InterruptedException {
        PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 64);
        int n = t.grass(Integer.MAX_VALUE, 65535, GrassContext.NORMAL, TOP, SIDE, new float[] { .5F, 1, 1, .5F });
        int s = t.grass(Integer.MAX_VALUE, 65535, GrassContext.SNOWY, TOP, SNOW_SIDE, null);
        int c = t.cube(Integer.MAX_VALUE, 65535, TOP, SIDE, 0);
        require(n != s && n != c && s != c, "same raw/meta have three independent explicit contexts");
        eq(t.find(Integer.MAX_VALUE, 65535, GrassContext.NORMAL), n, "normal key");
        eq(t.find(Integer.MAX_VALUE, 65535, GrassContext.SNOWY), s, "snowy key");
        eq(t.find(Integer.MAX_VALUE, 65535), c, "default key is not grass");
        eq(t.find(Integer.MAX_VALUE - 1, 65535, GrassContext.NORMAL), -1, "high raw bits do not collide");
        eq(t.find(Integer.MAX_VALUE, 65534, GrassContext.NORMAL), -1, "high metadata bits do not collide");
        for (int id : new int[] { n, s }) {
            eq(t.kind[id], Looks.SOLID, "grass stays solid");
            eq(t.top[id], 0, "top has no untinted part");
            eq(t.topTinted[id], 0x8040FF, "top colour uses canonical packing");
            near(t.topTintWeight[id], 1, "full top tint even under snow");
            eq(t.tint[id], Looks.TINT_GRASS, "grass resolver selector");
            eq(t.constantTint[id], -1, "no fixed tint");
            require(
                t.shape[id] == 0 && t.wetShape[id] == -1 && !t.spawnOn[id] && !t.spawnIn[id],
                "whole dry cube, no speculative spawn support");
        }
        eq(t.side[n], 0xFF8000, "normal base untinted");
        eq(t.sideTinted[n], 0x80FFFF, "normal overlay tinted");
        near(t.sideTintWeight[n], 1.0 / 3, "canonical coverage weight is .5/(1+.5), not pixel alpha composition");
        eq(t.side[s], 0x4080FF, "snow side retained");
        eq(t.sideTinted[s], 0, "snow side has no tinted colour");
        near(t.sideTintWeight[s], 0, "snow side never tinted");
        eq(t.grass(Integer.MAX_VALUE, 65535, GrassContext.NORMAL, null, null, null), n, "duplicate immutable payload");
        float[] zero = { 1, 1, 1, 0 };
        int emptyOverlay = t.grass(1, 0, GrassContext.NORMAL, TOP, SIDE, zero);
        near(t.sideTintWeight[emptyOverlay], 0, "transparent overlay leaves base only");
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                t.grass(2, 0, GrassContext.NORMAL, TOP, SIDE, zero);
            } catch (Throwable e) {
                error.set(e);
            }
        });
        worker.start();
        worker.join();
        require(error.get() instanceof IllegalStateException, "grass publication requires owner thread");
        t.invalidate();
        eq(t.find(Integer.MAX_VALUE, 65535, GrassContext.NORMAL), -1, "invalidated context cannot be reused");
        eq(t.grass(3, 0, GrassContext.SNOWY, null, null, null), -1, "invalidated context cannot allocate");
        require(
            t.isPrepared(n) && t.topTinted[n] == 0x8040FF,
            "old worker payload remains immutable after invalidation");
        PreparedBlockLooks full = new PreparedBlockLooks(new Object(), 2);
        eq(full.grass(1, 0, GrassContext.NORMAL, TOP, SIDE, zero), 1, "last slot allocated");
        eq(
            full.grass(1, 0, GrassContext.SNOWY, TOP, SNOW_SIDE, null),
            -1,
            "full table stays pending, not wrong context");
    }

    private static void invalid() {
        PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 3);
        for (int context : new int[] { -1, 0, 3, Integer.MAX_VALUE })
            expect(IllegalArgumentException.class, () -> t.grass(1, 0, context, TOP, SIDE, TOP));
        expect(IllegalArgumentException.class, () -> t.find(1, 0, 3));
        expect(IllegalArgumentException.class, () -> t.grass(-1, 0, GrassContext.NORMAL, TOP, SIDE, TOP));
        expect(IllegalArgumentException.class, () -> t.grass(1, 65536, GrassContext.NORMAL, TOP, SIDE, TOP));
        expect(NullPointerException.class, () -> t.grass(1, 0, GrassContext.NORMAL, TOP, SIDE, null));
        expect(IllegalArgumentException.class, () -> t.grass(1, 0, GrassContext.SNOWY, TOP, SIDE, TOP));
        for (float value : new float[] { Float.NaN, Float.POSITIVE_INFINITY, -.01F, 1.01F }) {
            for (int channel = 0; channel < 4; channel++) {
                float[] bad = { 1, 1, 1, 1 };
                bad[channel] = value;
                expect(IllegalArgumentException.class, () -> t.grass(1, 0, GrassContext.NORMAL, TOP, SIDE, bad));
            }
        }
        expect(
            IllegalArgumentException.class,
            () -> t.grass(1, 0, GrassContext.NORMAL, new float[] { 1, 1, 1, .5F }, SIDE, TOP));
        expect(
            IllegalArgumentException.class,
            () -> t.grass(1, 0, GrassContext.SNOWY, TOP, new float[] { 1, 1, 1, .5F }, null));
        eq(t.grass(1, 0, GrassContext.NORMAL, TOP, SIDE, TOP), 1, "invalid calls consume no IDs");
    }

    private static void captureAndBaker() {
        for (float coverage : new float[] { 0, 1F / 256, .25F, .5F, 1 }) {
            for (int tint : new int[] { 0, 0xFFFFFF, 0x40C080 }) {
                Scene scene = new Scene();
                scene.grassTint = tint;
                int normal = scene.table
                    .grass(GRASS, 0, GrassContext.NORMAL, TOP, SIDE, new float[] { .5F, 1, 1, coverage });
                int snowy = scene.table.grass(GRASS, 0, GrassContext.SNOWY, TOP, SNOW_SIDE, null);
                scene.table.cube(SNOW, 0, SNOW_SIDE, SNOW_SIDE, 0); // fixture only, not native snow geometry support
                scene.set(3, 15, 5, GRASS);
                ChunkCapture.Result first = ChunkCapture.capture(scene, scene, 0, 0);
                require(first.status() == ChunkCapture.Status.READY, "normal grass real capture ready");
                eq(
                    first.snapshot()
                        .sectionIds(0)[(15 << 8) | (5 << 4) | 3],
                    normal,
                    "captured normal context ID");
                scene.snapshot = first.snapshot();
                TileBaker baker = TileBaker
                    .forMeshing(new TileKey(TileKey.SURFACE, 0, 0), scene, scene.table, false, null);
                double w = coverage / (1.0 + coverage);
                checkAlbedo(baker, normal, true, 0x8040FF, tint, 1, 0);
                checkAlbedo(baker, normal, false, 0x80FFFF, tint, w, 0xFF8000);
                scene.set(3, 16, 5, SNOW);
                ChunkCapture.Result second = ChunkCapture.capture(scene, scene, 0, 0);
                require(
                    second.status() == ChunkCapture.Status.READY,
                    "snow-context capture with explicit fixture snow look");
                eq(
                    second.snapshot()
                        .sectionIds(0)[(15 << 8) | (5 << 4) | 3],
                    snowy,
                    "same raw metadata becomes snowy context");
                eq(
                    first.snapshot()
                        .sectionIds(0)[(15 << 8) | (5 << 4) | 3],
                    normal,
                    "prior snapshot not mutated by snow change");
                scene.snapshot = second.snapshot();
                baker = TileBaker.forMeshing(new TileKey(TileKey.SURFACE, 0, 0), scene, scene.table, false, null);
                checkAlbedo(baker, snowy, true, 0x8040FF, tint, 1, 0);
                checkAlbedo(baker, snowy, false, 0, tint, 0, 0x4080FF);
                scene.fail = true; // Any attempt to use the old live above-block reader throws.
                require(
                    ChunkCapture.capture(scene, scene, 0, 0)
                        .status() == ChunkCapture.Status.READY,
                    "grass capture resolves from detached state, never rereads the live neighbor");
                eq(scene.reads, 0, "all grass context came from copied section data");
                scene.set(3, 16, 5, 42);
                require(
                    ChunkCapture.capture(scene, scene, 0, 0)
                        .status() == ChunkCapture.Status.PENDING_APPEARANCE,
                    "unregistered upper raw remains pending");
            }
        }
    }

    private static void checkAlbedo(TileBaker baker, int id, boolean top, int coloured, int tint, double weight,
        int base) {
        baker.albedo(id, 3, 5, top);
        near(
            baker.albedoR,
            linear(base >> 16) * (1 - weight) + linear(coloured >> 16) * linear(tint >> 16) * weight,
            "real baker red independent expected");
        near(
            baker.albedoG,
            linear(base >> 8) * (1 - weight) + linear(coloured >> 8) * linear(tint >> 8) * weight,
            "real baker green independent expected");
        near(
            baker.albedoB,
            linear(base) * (1 - weight) + linear(coloured) * linear(tint) * weight,
            "real baker blue independent expected");
    }

    private static double linear(int value) {
        double v = (value & 255) / 255.0;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    private static final class RawSection implements ChunkCapture.Section {

        final int[] raw = new int[4096];

        public int blockId(int cell) {
            return raw[cell];
        }

        public int metadata(int cell) {
            return 0;
        }
    }

    private static final class Scene implements ChunkCapture.Access, ChunkCapture.Chunk, ChunkCapture.Inputs,
        GrassContext.Source, VoxelReader.Inputs {

        final Thread owner = Thread.currentThread();
        final Object token = new Object();
        Object epoch = token;
        final PreparedBlockLooks table = new PreparedBlockLooks(token, 8);
        final RawSection[] sections = new RawSection[16];
        ChunkSnapshot snapshot;
        boolean active = true, contextMissing, revokeInSession, fail;
        int mutation, reads, classifications, checks, readX, readY, readZ, rawOverride = Integer.MIN_VALUE,
            kindOverride = Integer.MIN_VALUE;
        int grassTint = 0x40C080;

        void clear() {
            Arrays.fill(sections, null);
            reads = classifications = 0;
        }

        void set(int x, int y, int z, int raw) {
            if (sections[y >> 4] == null) sections[y >> 4] = new RawSection();
            sections[y >> 4].raw[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = raw;
        }

        public boolean onThread() {
            return Thread.currentThread() == owner;
        }

        public boolean current() {
            checks++;
            return active;
        }

        public Object session() {
            checks++;
            if (revokeInSession) active = false;
            return epoch;
        }

        public int rawBlockId(int x, int y, int z) {
            reads++;
            readX = x;
            readY = y;
            readZ = z;
            if (fail) throw new UnsupportedOperationException("fixture raw failure");
            if (mutation == 1) active = false;
            if (mutation == 2) epoch = new Object();
            if (rawOverride != Integer.MIN_VALUE) return rawOverride;
            if (contextMissing) return -1;
            RawSection section = sections[y >> 4];
            return section == null ? 0 : section.raw[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }

        public int snowContext(int raw) {
            classifications++;
            if (mutation == 3) active = false;
            if (mutation == 4) epoch = new Object();
            if (kindOverride != Integer.MIN_VALUE) return kindOverride;
            return raw == SNOW || raw == LAYER ? GrassContext.SNOWY
                : raw == 0 || raw == 1 || raw == GRASS ? GrassContext.NORMAL : GrassContext.UNKNOWN;
        }

        public ChunkCapture.Chunk loaded(int x, int z) {
            return x == 0 && z == 0 ? this : null;
        }

        public int chunkX() {
            return 0;
        }

        public int chunkZ() {
            return 0;
        }

        public int minSectionY() {
            return 0;
        }

        public int sectionCount() {
            return 16;
        }

        public boolean hasSkyLight() {
            return true;
        }

        public ChunkCapture.Section section(int index) {
            return sections[index];
        }

        public int blockLight(int section, int cell) {
            return 0;
        }

        public int skyLight(int section, int cell) {
            return 15;
        }

        public PreparedBlockLooks looks() {
            return table;
        }

        public int airId() {
            return table.airId();
        }

        public Object lookDomain() {
            return table;
        }

        public int rawAirId() {
            return 0;
        }

        public int lookId(int raw, int meta, int x, int y, int z, int aboveRawId) {
            if (raw == 0) return table.airId();
            if (raw == GRASS) {
                int context = y == 255 ? GrassContext.NORMAL : snowContext(aboveRawId);
                return context == GrassContext.UNKNOWN ? -1 : table.find(raw, meta, context);
            }
            return table.find(raw, meta);
        }

        public ChunkSnapshot.Tints tints(int x, int z, int[] surface) {
            int[] grass = new int[256], other = new int[256];
            Arrays.fill(grass, grassTint);
            Arrays.fill(other, 0xFFFFFF);
            return new ChunkSnapshot.Tints(grass, other, other, other);
        }

        public int fogColor(int x, int z) {
            return 0x330808;
        }

        public ChunkSnapshot chunk(int x, int z) {
            return x == 0 && z == 0 ? snapshot : null;
        }

        public int minY() {
            return 0;
        }

        public int maxY() {
            return 256;
        }

        public int topY() {
            return snapshot == null ? 0 : snapshot.maxY + 1;
        }

        public boolean isSeen(int x, int y, int z) {
            return true;
        }
    }

    private static void eq(int actual, int expected, String message) {
        require(actual == expected, message + ": " + actual + " != " + expected);
        put(actual);
    }

    private static void near(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 0.000002, message + ": " + actual + " != " + expected);
        put((int) Math.round(actual * 100000));
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            require(type.isInstance(t), "wrong exception: " + t);
            return;
        }
        throw new AssertionError("missing " + type.getName());
    }

    private static void put(int value) {
        for (int shift = 24; shift >= 0; shift -= 8) HASH.update((byte) (value >>> shift));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
