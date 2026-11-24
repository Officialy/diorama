package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the canonical appearance core without game, model or GL classes. */
public final class AppearanceProbe {

    public static void main(String[] args) throws Exception {
        Synthetic looks = new Synthetic(64, 16);
        for (int id = 0; id < 64; id++) {
            require(looks.kind[id] == -1 && looks.wetShape[id] == -1, "initial sentinels");
            require(looks.ensure(id) == id, "ensure identity");
            require(looks.complete(id), "complete payload");
            looks.ensure(id);
            require(looks.fills[id] == 1, "once-only fill");
        }
        require(looks.ensure(-1) == -1 && looks.ensure(looks.kind.length) == looks.kind.length, "out-of-domain ensure");
        require(looks.shapes[0].length == 0 && looks.shapes.length >= 64, "shape growth and zero sentinel");
        require(looks.shape[62] == looks.shape[63] && looks.sameLook(62, 63), "equivalent entries");
        require(!looks.sameLook(1, 7), "distinct payloads");
        shapeAndFaces(looks);
        comparatorFields();
        colourFields();
        copycats();
        concurrentPublication();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int id = 0; id < 64; id++) {
            put(digest, looks.kind[id]);
            put(digest, looks.top[id]);
            put(digest, looks.side[id]);
            put(digest, looks.shape[id]);
            put(digest, looks.wetShape[id]);
            for (float v : looks.shapes[looks.shape[id]]) put(digest, Float.floatToIntBits(v));
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS appearance payloads, shapes, colours, copycats and bounded publication; MC/GL/TileBaker UNTESTED");
    }

    private static void shapeAndFaces(Synthetic looks) {
        float[] a = { 0, 0, 0, 0.25F, 0.5F, 1 };
        int one = looks.addShape(a);
        require(looks.addShape(a.clone()) == one, "ordered shape dedup");
        float[] two = { 0, 0, 0, 0.25F, 0.5F, 1, 0.5F, 0, 0, 0.75F, 1, 1 };
        int ordered = looks.addShape(two);
        float[] reversed = { 0.5F, 0, 0, 0.75F, 1, 1, 0, 0, 0, 0.25F, 0.5F, 1 };
        require(looks.addShape(reversed) != ordered, "do not reorder boxes");
        require(Arrays.equals(looks.shapes[ordered], two), "shape contents");
        ShapeFaces faces = looks.faces(one);
        require(faces == looks.faces(one), "face cache");
        require(Arrays.equals(faces.boxes, new int[] { 0, ShapeFaces.pack(32, 64, 128) }), "packed boxes");
        require(
            Arrays.equals(faces.top, new int[] { ShapeFaces.pack(0, 64, 0), ShapeFaces.pack(32, 64, 128) }),
            "slab top");
        require(faces.wetCut && faces.wetTop.length == 2 && faces.wetSouth.length > 0, "waterlogged faces");
        require(faces.seen(0.8, 0.6).length == 2, "visible faces");
    }

    private static void comparatorFields() {
        Synthetic t = new Synthetic(64, 0);
        t.ensure(62);
        t.ensure(63);
        require(t.sameLook(62, 63), "sameLook baseline");
        // Deliberate single-thread fixture mutation only, to test every comparator term.
        t.kind[63]++;
        require(!t.sameLook(62, 63), "kind equality");
        t.kind[63]--;
        t.top[63]++;
        require(!t.sameLook(62, 63), "top equality");
        t.top[63]--;
        t.topTinted[63]++;
        require(!t.sameLook(62, 63), "top tinted equality");
        t.topTinted[63]--;
        t.side[63]++;
        require(!t.sameLook(62, 63), "side equality");
        t.side[63]--;
        t.sideTinted[63]++;
        require(!t.sameLook(62, 63), "side tinted equality");
        t.sideTinted[63]--;
        t.topTintWeight[63] += 1;
        require(!t.sameLook(62, 63), "top weight equality");
        t.topTintWeight[63] -= 1;
        t.sideTintWeight[63] += 1;
        require(!t.sameLook(62, 63), "side weight equality");
        t.sideTintWeight[63] -= 1;
        t.tint[63]++;
        require(!t.sameLook(62, 63), "tint equality");
        t.tint[63]--;
        t.constantTint[63]++;
        require(!t.sameLook(62, 63), "constant tint equality");
        t.constantTint[63]--;
        t.emission[63]++;
        require(!t.sameLook(62, 63), "emission equality");
        t.emission[63]--;
        t.spawnOn[63] = !t.spawnOn[63];
        require(!t.sameLook(62, 63), "spawn on equality");
        t.spawnOn[63] = !t.spawnOn[63];
        t.spawnIn[63] = !t.spawnIn[63];
        require(!t.sameLook(62, 63), "spawn in equality");
        t.spawnIn[63] = !t.spawnIn[63];
        t.shape[63]++;
        require(!t.sameLook(62, 63), "shape equality");
        t.shape[63]--;
        t.wetShape[63]++;
        require(!t.sameLook(62, 63), "wet shape equality");
        t.wetShape[63]--;
        require(t.sameLook(62, 63), "comparator fixture restored");
    }

    private static void colourFields() {
        Synthetic t = new Synthetic(6, 0);
        FaceColor top = new FaceColor();
        FaceColor side = new FaceColor();
        top.add(new float[] { 1, 0, 0, 1 }, -1);
        top.add(new float[] { 0, 1, 0, 1 }, 2);
        side.add(new float[] { 0, 0, 1, 0.5F }, -1);
        require(!top.isEmpty() && top.tintIndex() == 2 && top.coverage() == 1, "face colour metadata");
        t.setColours(0, top, side);
        require(t.top[0] == 0xFF0000 && t.topTinted[0] == 0x00FF00 && t.topTintWeight[0] == 0.5F, "top colour split");
        require(t.side[0] == 0x0000FF && t.sideTinted[0] == 0 && t.sideTintWeight[0] == 0, "side colour split");
        require(FaceColor.pack(-1, 0.5F, 2) == 0x0080FF, "colour clamp");
    }

    private static void copycats() throws Exception {
        Synthetic t = new Synthetic(64, 2);
        int id = t.copycat(1, 4);
        require(id == 64 && t.copycat(1, 4) == id, "stable copycat pair");
        require(
            t.kind[id] == Looks.GLASS && t.shape[id] == t.shape[1] && t.wetShape[id] == t.wetShape[1],
            "frame geometry and water");
        require(t.spawnOn[id] == t.spawnOn[1] && t.spawnIn[id] == t.spawnIn[1], "frame spawn rules");
        require(sameMaterial(t, id, 4), "material payload");
        int plant = t.copycat(5, 1);
        require(plant == 65 && t.kind[plant] == Looks.PLANT, "plant frame");
        require(t.copycat(1, 2) == 1 && t.warnings == 1, "full copycat fallback");
        require(t.copycat(1, 3) == 1 && t.warnings == 1, "full warning once sequentially");
        require(t.copycat(1, 0) == 1 && t.copycat(0, 1) == 0, "air and invalid frame");
        require(t.copycat(-1, 1) == -1 && t.copycat(1, -1) == 1, "invalid ids");
        require(new Synthetic(64, 0).copycat(1, 4) == 1, "no reserved slots");
        // Spare slots isolate pair publication from the existing at-capacity fallback.
        Synthetic race = new Synthetic(64, 6);
        runReaders(6, worker -> {
            int made = race.copycat(1, 4);
            // Concurrent map return publishes the complete payload; do not join before checking it.
            require(
                made == 64 && sameMaterial(race, made, 4) && race.shape[made] == race.shape[1],
                "racing pair publication");
        });
        Synthetic tight = new Synthetic(64, 1);
        runReaders(6, worker -> {
            int made = tight.copycat(1, 4);
            // At capacity an in-flight pair can temporarily fall back to the frame, as upstream does.
            require(made == 1 || made == 64 && sameMaterial(tight, made, 4), "bounded capacity fallback");
        });
        require(tight.copycat(1, 4) == 64, "published pair remains usable at capacity");
    }

    private static boolean sameMaterial(Synthetic t, int a, int b) {
        return t.top[a] == t.top[b] && t.topTinted[a] == t.topTinted[b]
            && t.topTintWeight[a] == t.topTintWeight[b]
            && t.side[a] == t.side[b]
            && t.sideTinted[a] == t.sideTinted[b]
            && t.sideTintWeight[a] == t.sideTintWeight[b]
            && t.tint[a] == t.tint[b]
            && t.constantTint[a] == t.constantTint[b]
            && t.emission[a] == t.emission[b];
    }

    private static void concurrentPublication() throws Exception {
        for (int round = 0; round < 12; round++) {
            Synthetic t = new Synthetic(128, 4);
            runReaders(6, worker -> {
                t.ensure(17);
                require(t.complete(17), "same id publication");
                for (int i = 0; i < 128; i++) {
                    int id = (i * 37 + worker * 19) & 127;
                    t.ensure(id);
                    // No synchronization with the writer beyond production ensure/shapes reads.
                    require(t.complete(id), "payload and shape publication");
                }
            });
            for (int id = 0; id < 128; id++) require(t.fills[id] == 1, "concurrent exactly-once fill");
        }
    }

    private interface Reader {

        void read(int worker) throws Exception;
    }

    private static void runReaders(int count, Reader reader) throws Exception {
        CyclicBarrier start = new CyclicBarrier(count);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            final int worker = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    reader.read(worker);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "appearance-reader-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(15000);
            if (thread.isAlive()) throw new AssertionError("appearance reader timed out");
        }
        if (failure.get() != null) throw new AssertionError("appearance reader failed", failure.get());
    }

    private static final class Synthetic extends Looks {

        private final int[] fills;
        private int warnings;

        Synthetic(int count, int extra) {
            super(count, count + extra);
            fills = new int[count];
        }

        @Override
        protected synchronized void make(int id) {
            if ((byte) KINDS.getAcquire(kind, id) != NOT_YET) return;
            int v = value(id);
            fills[id]++;
            top[id] = 0x110000 + v;
            topTinted[id] = 0x220000 + v;
            topTintWeight[id] = (v % 4) / 4.0F;
            side[id] = 0x330000 + v;
            sideTinted[id] = 0x440000 + v;
            sideTintWeight[id] = (v % 3) / 4.0F;
            tint[id] = (byte) (v % 5);
            emission[id] = (byte) (v % 16);
            constantTint[id] = -1;
            spawnOn[id] = (v & 1) == 0;
            spawnIn[id] = (v & 2) == 0;
            shape[id] = internShape(box(v));
            wetShape[id] = v % 6 == SOLID ? 2 : -1;
            publish(id, (byte) (v % 6));
        }

        @Override
        protected void warnCopycatsFull() {
            warnings++;
        }

        synchronized int addShape(float[] boxes) {
            return internShape(boxes);
        }

        boolean complete(int id) {
            int v = value(id);
            int index = shape[id];
            float[][] published = shapes;
            return kind[id] == v % 6 && top[id] == 0x110000 + v
                && topTinted[id] == 0x220000 + v
                && side[id] == 0x330000 + v
                && sideTinted[id] == 0x440000 + v
                && topTintWeight[id] == (v % 4) / 4.0F
                && sideTintWeight[id] == (v % 3) / 4.0F
                && tint[id] == v % 5
                && emission[id] == v % 16
                && constantTint[id] == -1
                && spawnOn[id] == ((v & 1) == 0)
                && spawnIn[id] == ((v & 2) == 0)
                && wetShape[id] == (v % 6 == SOLID ? 2 : -1)
                && index > 0
                && index < published.length
                && Arrays.equals(published[index], box(v));
        }

        private static int value(int id) {
            return id == 63 ? 62 : id;
        }

        private static float[] box(int v) {
            return new float[] { 0, 0, 0, (v + 1) / 256.0F, 0.5F, 1 };
        }
    }

    private static void put(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
