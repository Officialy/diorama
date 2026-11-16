package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;

/** Standalone CPU contract probe; real capture interfaces, never substitute Minecraft classes. */
public final class PreparedBlockLooksProbe {

    private static final AtomicInteger assertions = new AtomicInteger();
    private static final float[] TOP = { 1, 0.5F, 0, 1 };
    private static final float[] SIDE = { 0, 0.25F, 1, 1 };
    private static MessageDigest digest;

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        allocation();
        extendedKeys();
        rejectedInputs();
        lifecycle();
        wrongThread();
        publication();
        capture();
        extendedCapture();
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS prepared looks; assertions=" + assertions.get()
                + "; allocation, rejection atomicity, epochs, owner guard, bounded publication, ChunkCapture"
                + "; MC/GL/texture auditing/result acceptance UNTESTED");
    }

    private static void allocation() {
        expect(IllegalArgumentException.class, () -> new PreparedBlockLooks(new Object(), 0));
        expect(IllegalArgumentException.class, () -> new PreparedBlockLooks(new Object(), -1));
        expect(NullPointerException.class, () -> new PreparedBlockLooks(null, 2));
        PreparedBlockLooks airOnly = new PreparedBlockLooks(new Object(), 1);
        fields(airOnly, 0, Looks.AIR, 0, 0, 0);
        require(airOnly.cube(1, 0, TOP, SIDE, 0) == -1, "capacity includes reserved air");
        require(airOnly.find(1, 0) == -1, "full table does not register key");

        Object epoch = new Object();
        PreparedBlockLooks t = new PreparedBlockLooks(epoch, 97);
        require(t.session() == epoch && t.airId() == 0, "opaque session and air zero");
        fields(t, 0, Looks.AIR, 0, 0, 0);
        require(t.find(0, 0) == -1, "raw zero is not implicitly air");
        expect(IllegalStateException.class, () -> t.ensure(1));
        require(!t.isPrepared(1) && t.kind[1] == -1, "unknown ensure cannot complete make");
        int[] rawIds = { 0, 1, 255, 4095, 0x0fffffff, Integer.MAX_VALUE };
        int expected = 1;
        for (int raw : rawIds) {
            for (int meta = 0; meta < 16; meta++) {
                int id = t.cube(raw, meta, TOP, SIDE, meta);
                require(id == expected++ && id != t.airId(), "dense nonzero IDs without key collision");
                require(t.find(raw, meta) == id, "raw and metadata lookup");
                fields(t, id, Looks.SOLID, 0xFF8000, 0x0040FF, meta);
                require(t.ensure(id) == id, "prepared ensure identity");
                put(payload(t, id));
            }
        }
        String before = payload(t, 1);
        require(t.cube(0, 0, SIDE, TOP, 15) == 1, "duplicate stable ID at capacity");
        require(before.equals(payload(t, 1)), "duplicate cannot rewrite payload");
        require(t.cube(0, 0, null, null, -1) == 1, "duplicate is immutable lookup, not new validation");
        require(t.cube(42, 0, TOP, SIDE, 0) == -1 && t.find(42, 0) == -1, "full miss stays pending");
        require(t.find(Integer.MAX_VALUE, 15) == 96, "highest raw ID and metadata survive");
    }

    private static void extendedKeys() {
        // Adjacent IDs expose overlap from nibble packing; high IDs expose int shifts/truncation.
        int[] rawIds = { 0, 1, 16, 65535, 65536, 0x00ffffff, 0x01000000, 0x01000001, Integer.MAX_VALUE };
        int[] metadata = { 0, 1, 15, 16, 255, 256, 65535 };
        PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 1 + rawIds.length * metadata.length);
        int expected = 1;
        for (int raw : rawIds) {
            for (int meta : metadata) {
                require(t.find(raw, meta) == -1, "extended key must not alias an existing key");
                int id = t.cube(raw, meta, TOP, SIDE, 5);
                require(id == expected++, "extended keys allocate distinct dense IDs");
                fields(t, id, Looks.SOLID, 0xFF8000, 0x0040FF, 5);
                put(raw);
                put(meta);
                put(id);
            }
        }
        expected = 1;
        for (int raw : rawIds) {
            for (int meta : metadata) {
                int id = expected++;
                require(t.find(raw, meta) == id, "all extended keys survive later insertions without collision");
                require(t.cube(raw, meta, SIDE, TOP, 0) == id, "extended duplicate retains ID at capacity");
                fields(t, id, Looks.SOLID, 0xFF8000, 0x0040FF, 5);
            }
        }
    }

    private static void rejectedInputs() {
        PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 2);
        String before = payload(t, 0) + payload(t, 1);
        int[][] keys = { { -1, 0 }, { Integer.MIN_VALUE, 0 }, { 1, -1 }, { 1, 65536 }, { 1, Integer.MAX_VALUE } };
        for (int[] key : keys) {
            expect(IllegalArgumentException.class, () -> t.find(key[0], key[1]));
            expect(IllegalArgumentException.class, () -> t.cube(key[0], key[1], TOP, SIDE, 0));
            unchanged(t, before);
        }
        float[][] bad = { new float[0], new float[3], new float[5], { 1, 1, 1, 0 }, { 1, 1, 1, 0.5F }, { 1, 1, 1, -1 },
            { 1, 1, 1, 2 }, { 1, 1, 1, Float.NaN }, { 1, 1, 1, Float.POSITIVE_INFINITY },
            { 1, 1, 1, Float.NEGATIVE_INFINITY } };
        for (float[] face : bad) rejectFace(t, face, before);
        float[] badValues = { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01F, 1.01F };
        for (int channel = 0; channel < 3; channel++) {
            for (float value : badValues) {
                float[] face = TOP.clone();
                face[channel] = value;
                rejectFace(t, face, before);
            }
        }
        expect(NullPointerException.class, () -> t.cube(7, 0, null, SIDE, 0));
        unchanged(t, before);
        expect(NullPointerException.class, () -> t.cube(7, 0, TOP, null, 0));
        unchanged(t, before);
        for (int light : new int[] { -1, 16, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            expect(IllegalArgumentException.class, () -> t.cube(7, 0, TOP, SIDE, light));
            unchanged(t, before);
        }
        float[] top = TOP.clone();
        float[] side = SIDE.clone();
        require(t.cube(7, 0, top, side, 15) == 1, "all rejections consume no ID");
        Arrays.fill(top, Float.NaN);
        Arrays.fill(side, -1);
        fields(t, 1, Looks.SOLID, 0xFF8000, 0x0040FF, 15);
        require(t.find(7, 0) == 1, "caller arrays not retained");
        put(payload(t, 1));
    }

    private static void rejectFace(PreparedBlockLooks t, float[] face, String before) {
        expect(IllegalArgumentException.class, () -> t.cube(7, 0, face, SIDE, 0));
        unchanged(t, before);
        expect(IllegalArgumentException.class, () -> t.cube(7, 0, TOP, face, 0));
        unchanged(t, before);
    }

    private static void unchanged(PreparedBlockLooks t, String before) {
        require(before.equals(payload(t, 0) + payload(t, 1)), "rejection leaves every payload field unchanged");
        require(t.find(7, 0) == -1 && !t.isPrepared(1), "rejection leaves key and readiness unchanged");
    }

    private static void lifecycle() {
        Object oldEpoch = new Object();
        Object newEpoch = new Object();
        PreparedBlockLooks old = new PreparedBlockLooks(oldEpoch, 3);
        int id = old.cube(7, 3, TOP, SIDE, 5);
        String before = payload(old, id);
        old.invalidate();
        old.invalidate();
        require(old.find(7, 3) == -1, "invalidation stops old lookup");
        require(old.cube(7, 3, SIDE, TOP, 0) == -1, "invalidation stops duplicate preparation");
        require(old.cube(8, 0, TOP, SIDE, 0) == -1 && !old.isPrepared(2), "invalidation stops new allocation");
        require(old.ensure(id) == id && old.isPrepared(id), "inflight old payload still prepared");
        require(before.equals(payload(old, id)), "invalidation retains old payload");
        expect(IllegalStateException.class, () -> old.ensure(2));
        PreparedBlockLooks fresh = new PreparedBlockLooks(newEpoch, 3);
        require(fresh.find(7, 3) == -1, "new epoch inherits no key");
        require(fresh.cube(7, 3, SIDE, TOP, 0) == id, "numeric IDs can repeat across domains");
        require(fresh.session() != old.session(), "epoch identities distinct");
        fields(fresh, id, Looks.SOLID, 0x0040FF, 0xFF8000, 0);
        require(before.equals(payload(old, id)), "new epoch cannot alter old payload");
        put(payload(old, id));
        put(payload(fresh, id));
    }

    private static void wrongThread() throws Exception {
        PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 3);
        int id = t.cube(1, 0, TOP, SIDE, 9);
        String before = payload(t, 0) + payload(t, 1) + payload(t, 2);
        runWorker(() -> {
            expect(IllegalStateException.class, () -> t.find(1, 0));
            expect(IllegalStateException.class, () -> t.cube(2, 0, TOP, SIDE, 0));
            expect(IllegalStateException.class, () -> t.cube(1, 0, SIDE, TOP, 0));
            expect(IllegalStateException.class, t::invalidate);
            expect(IllegalStateException.class, () -> t.ensure(2));
            require(!t.isPrepared(2), "worker cannot lazily prepare unknown");
            require(t.ensure(id) == id, "worker may acquire prepared look");
            fields(t, id, Looks.SOLID, 0xFF8000, 0x0040FF, 9);
        });
        require(before.equals(payload(t, 0) + payload(t, 1) + payload(t, 2)), "wrong thread has zero payload effects");
        require(t.find(1, 0) == id && t.find(2, 0) == -1, "wrong thread has zero map/invalidation effects");
        require(t.cube(2, 0, TOP, SIDE, 0) == 2, "wrong thread consumes no capacity");
        put(payload(t, 2));
    }

    private static void publication() throws Exception {
        // Worker signals before preparation. No owner-to-worker handoff after writes:
        // readiness comes solely from isPrepared/getAcquire paired with publish/setRelease.
        for (int round = 0; round < 16; round++) {
            PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 2);
            CountDownLatch waiting = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            int emission = round;
            Thread worker = worker(() -> {
                waiting.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!t.isPrepared(1)) {
                    if (System.nanoTime() - deadline >= 0) throw new AssertionError("publication timeout");
                    Thread.onSpinWait();
                }
                fields(t, 1, Looks.SOLID, 0xFF8000, 0x0040FF, emission);
                require(t.ensure(1) == 1, "worker reads only prepared payload");
            }, failure);
            int published = -1;
            try {
                require(waiting.await(5, TimeUnit.SECONDS), "publication worker started");
                published = t.cube(100, round, TOP, SIDE, emission);
                // Do not update the shared assertion counter after cube until the worker is done:
                // that atomic operation would supply an unrelated visibility edge to its checks.
            } finally {
                finish(worker, failure);
            }
            require(published == 1, "owner publishes expected ID");
            put(payload(t, 1));
        }
    }

    private static void capture() throws Exception {
        Scene scene = new Scene();
        ChunkCapture.Result pending = scene.capture();
        require(
            pending.status() == ChunkCapture.Status.PENDING_APPEARANCE,
            "unknown raw cube stays pending, never AIR");
        require(
            pending.snapshot() == null && pending.session() == scene.epoch,
            "pending carries no fabricated snapshot");
        require(!scene.table.isPrepared(1), "capture cannot invoke lazy make");
        int id = scene.table.cube(4095, 15, TOP, SIDE, 12);
        ChunkCapture.Result ready = scene.capture();
        require(
            ready.status() == ChunkCapture.Status.READY && ready.session() == scene.epoch,
            "real ChunkCapture ready");
        ChunkSnapshot snapshot = ready.snapshot();
        require(snapshot != null && snapshot.lookDomain() == scene.table, "snapshot binds actual prepared table");
        require(
            snapshot.airId() == 0 && snapshot.maxY == 3 && snapshot.surface(2, 4) == 3,
            "prepared cube height and air");
        int[] ids = snapshot.sectionIds(0);
        require(
            ids.length == 4096 && ids[Scene.CELL] == id && ids[0] == 0,
            "mixed capture preserves solid and air IDs");
        require(snapshot.blockLight(0, 2, 3, 4) == 12 && snapshot.skyLight(0, 2, 3, 4) == 15, "capture retains light");
        int calls = scene.lookCalls;
        runWorker(() -> {
            int prepared = snapshot.sectionIds(0)[Scene.CELL];
            require(scene.table.isPrepared(prepared), "worker acquires captured ID");
            fields(scene.table, prepared, Looks.SOLID, 0xFF8000, 0x0040FF, 12);
        });
        require(scene.lookCalls == calls, "worker reads no capture/producer callbacks");
        scene.unknown = true;
        require(
            scene.capture()
                .status() == ChunkCapture.Status.PENDING_APPEARANCE,
            "second unknown key is not implicit air");
        scene.unknown = false;
        scene.table.invalidate();
        ChunkCapture.Result invalidated = scene.capture();
        require(
            invalidated.status() == ChunkCapture.Status.PENDING_APPEARANCE && invalidated.snapshot() == null,
            "invalidated table cannot recapture old solid");
        require(
            snapshot.sectionIds(0)[Scene.CELL] == id && scene.table.isPrepared(id),
            "old snapshot payload survives invalidation");
        scene.accessEpoch = new Object();
        require(
            scene.capture()
                .status() == ChunkCapture.Status.STALE_SESSION,
            "capture rejects mixed epochs");
        put(snapshot.maxY);
        for (int value : ids) put(value);
    }

    private static void extendedCapture() {
        for (int raw : new int[] { 65536, 0x00ffffff, 0x01000000, 0x01000001, Integer.MAX_VALUE }) {
            for (int meta : new int[] { 16, 255, 256, 65535 }) {
                Scene scene = new Scene();
                scene.raw = raw;
                scene.meta = meta;
                require(
                    scene.capture()
                        .status() == ChunkCapture.Status.PENDING_APPEARANCE,
                    "extended unprepared key stays pending");
                int id = scene.table.cube(raw, meta, TOP, SIDE, 12);
                ChunkCapture.Result result = scene.capture();
                require(
                    result.status() == ChunkCapture.Status.READY,
                    "extended key captures through prepared resolver");
                require(
                    scene.resolvedRaw == raw && scene.resolvedMeta == meta,
                    "resolver receives complete extended key");
                require(
                    result.snapshot()
                        .sectionIds(0)[Scene.CELL] == id,
                    "extended captured ID matches prepared key");
                fields(scene.table, id, Looks.SOLID, 0xFF8000, 0x0040FF, 12);
                put(raw);
                put(meta);
                put(id);
            }
        }
    }

    private static final class Scene implements ChunkCapture.Chunk, ChunkCapture.Section, ChunkCapture.Inputs {

        static final int CELL = (3 << 8) | (4 << 4) | 2;
        final Thread owner = Thread.currentThread();
        final Object epoch = new Object();
        final PreparedBlockLooks table = new PreparedBlockLooks(epoch, 3);
        Object accessEpoch = epoch;
        boolean unknown;
        int raw = 4095, meta = 15;
        int resolvedRaw = -1, resolvedMeta = -1;
        int lookCalls;
        final ChunkCapture.Access access = new ChunkCapture.Access() {

            public boolean onThread() {
                return Thread.currentThread() == owner;
            }

            public boolean current() {
                return true;
            }

            public Object session() {
                return accessEpoch;
            }

            public ChunkCapture.Chunk loaded(int x, int z) {
                return Scene.this;
            }
        };

        ChunkCapture.Result capture() {
            return ChunkCapture.capture(access, this, 0, 0);
        }

        public Object session() {
            return table.session();
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
            return 1;
        }

        public boolean hasSkyLight() {
            return true;
        }

        public ChunkCapture.Section section(int index) {
            return this;
        }

        public int blockId(int cell) {
            return cell == CELL ? (unknown ? 4094 : raw) : 0;
        }

        public int metadata(int cell) {
            return cell == CELL ? meta : 0;
        }

        public int blockLight(int index, int cell) {
            return cell == CELL ? 12 : 0;
        }

        public int skyLight(int index, int cell) {
            return 15;
        }

        public Looks looks() {
            return table;
        }

        public int airId() {
            return table.airId();
        }

        public int lookId(int raw, int meta, int x, int y, int z) {
            lookCalls++;
            if (raw != 0) {
                resolvedRaw = raw;
                resolvedMeta = meta;
            }
            return raw == 0 ? table.airId() : table.find(raw, meta);
        }

        public ChunkSnapshot.Tints tints(int x, int z, int[] surface) {
            int[] white = new int[256];
            Arrays.fill(white, 0xFFFFFF);
            return new ChunkSnapshot.Tints(white, white, white, white);
        }

        public int fogColor(int x, int z) {
            return 0x123456;
        }
    }

    private static void fields(PreparedBlockLooks t, int id, byte kind, int top, int side, int light) {
        require(t.isPrepared(id) && t.kind[id] == kind, "published material kind");
        require(t.top[id] == top && t.side[id] == side, "packed top and side colors");
        require(t.topTinted[id] == 0 && t.sideTinted[id] == 0, "no tinted colors");
        require(t.topTintWeight[id] == 0 && t.sideTintWeight[id] == 0, "no tint weights");
        require(t.tint[id] == Looks.TINT_NONE && t.constantTint[id] == -1, "no contextual or constant tint");
        require(t.emission[id] == light, "emission preserved");
        require(!t.spawnOn[id] && !t.spawnIn[id], "spawn deliberately unspecified");
        require(t.shape[id] == 0 && t.wetShape[id] == -1, "whole dry cube shape");
        require(t.shapes.length == 1 && t.shapes[0].length == 0, "no retained shape geometry");
    }

    private static String payload(PreparedBlockLooks t, int id) {
        return Arrays.toString(
            new int[] { t.kind[id], t.tint[id], t.emission[id], t.top[id], t.side[id], t.topTinted[id],
                t.sideTinted[id], Float.floatToIntBits(t.topTintWeight[id]), Float.floatToIntBits(t.sideTintWeight[id]),
                t.constantTint[id], t.spawnOn[id] ? 1 : 0, t.spawnIn[id] ? 1 : 0, t.shape[id], t.wetShape[id] })
            + Arrays.deepToString(t.shapes);
    }

    private static void runWorker(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        finish(worker(action, failure), failure);
    }

    private static Thread worker(Runnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "prepared-looks-probe");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void finish(Thread thread, AtomicReference<Throwable> failure) throws Exception {
        thread.join(10000);
        if (thread.isAlive()) {
            thread.interrupt();
            throw new AssertionError("worker exceeded bounded join");
        }
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable problem) {
            require(type.isInstance(problem), "expected " + type.getSimpleName() + ", got " + problem);
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        assertions.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static void put(int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void put(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        put(bytes.length);
        digest.update(bytes);
    }
}
