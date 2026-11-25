package dev.diorama.bake;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.OpaquePng;
import dev.diorama.gtnh.capture.WaterPng;

/** Real PNG/capture/shared-baker fixtures, never Minecraft registry/resource/GL execution. */
public final class WaterProbe {

    private static final MessageDigest HASH = hash();
    private static int assertions;
    private static final float[] WATER_COLOUR = { .25F, .5F, 1, .5F };
    private static final int STILL = 90000, FLOW = 90001, AIR = 7;

    public static void main(String[] args) throws Exception {
        pngs();
        rejectedPngs();
        rectangularSprite();
        payloads();
        captureAndBaker();
        StringBuilder hex = new StringBuilder();
        for (byte b : HASH.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS water: " + assertions
                + " assertions; first original frame/alpha/PNG bounds/stream ownership, metadata keys, WATER publication, real capture/tint/surface/transmission; native registry/resource/animation hooks/game UNTESTED");
    }

    private static void pngs() throws IOException {
        boolean cache = ImageIO.getUseCache();
        Random random = new Random(0x15A7E);
        for (int size : new int[] { 1, 2, 16, 32 }) {
            for (int frames : new int[] { 1, 2, 8 }) {
                int[] pixels = new int[size * size * frames];
                for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
                byte[] data = png(size, size * frames, pixels);
                float[] expected = average(pixels, size * size);
                float[] result = decode(data, size, size, frames > 1);
                floats(result, expected, "top-left original frame, not other frames/timeline");
                if (frames > 1) require(
                    OpaquePng.averageCutout(new ByteArrayInputStream(data), size, size) == null,
                    "general cutout path still rejects animation strip");
            }
        }
        for (int alpha = 0; alpha < 256; alpha++) {
            int[] pixels = { 0xFF204060, (alpha << 24) | 0xC08040, 0x00FF0000, 0x1940FF40 };
            byte[] bytes = png(2, 2, pixels);
            floats(
                decode(bytes, 2, 2, false),
                average(pixels, 4),
                "all alpha values use canonical weighting/26 threshold");
            require(
                OpaquePng.average(new ByteArrayInputStream(bytes), 2, 2) == null,
                "water partial alpha exception never relaxes opaque cubes");
        }
        floats(
            decode(png(1, 2, new int[] { 0x00123456, 0xFFFFFFFF }), 1, 1, true),
            new float[4],
            "invisible first frame stays empty despite visible later frame");
        require(ImageIO.getUseCache() == cache, "no global ImageIO cache mutation");
    }

    private static void rectangularSprite() throws IOException {
        int[] pixels = { 0xFFFF0000, 0x8000FF00, 0xFF0000FF, 0xFFFFFFFF, 0, 0 };
        byte[] data = png(3, 2, pixels);
        floats(
            WaterPng.averageSpriteFrame(new ByteArrayInputStream(data), 3, 1, true),
            average(pixels, 3),
            "rectangular animated sprite first frame");
        require(WaterPng.averageSpriteFrame(new ByteArrayInputStream(data), 3, 1, false) == null,
            "unanimated sprite cannot silently accept extra frames");
        require(decode(data, 3, 1, true) == null, "strict water contract unchanged");
    }

    private static void rejectedPngs() throws IOException {
        byte[] valid = png(2, 4, new int[8]);
        for (int[] size : new int[][] { { 0, 0 }, { -1, -1 }, { 2, 1 }, { 1, 2 }, { 4097, 4097 },
            { Integer.MAX_VALUE, Integer.MAX_VALUE }, { 1, 1 }, { 3, 3 } })
            require(decode(valid, size[0], size[1], true) == null, "invalid requested sprite dimensions");
        require(decode(valid, 2, 2, false) == null, "unanimated image must be square");
        require(decode(png(2, 3, new int[6]), 2, 2, true) == null, "partial strip frame rejected");
        require(decode(png(4, 4, new int[16]), 2, 2, true) == null, "horizontal/grid animations outside legacy layout");
        for (int[] bounds : new int[][] { { 2, 65538 }, { 4096, 20480 }, { 4097, 4097 } }) {
            // Header-only fixtures prove dimensions are rejected before any image-data allocation.
            require(decode(header(bounds[0], bounds[1]), bounds[0], bounds[0], true) == null, "bounded source headers");
        }
        require(decode(new byte[] { 1, 2, 3 }, 1, 1, true) == null, "non-PNG rejected");
        for (int length : new int[] { 8, 20, 32 }) {
            try {
                require(
                    decode(Arrays.copyOf(valid, length), 2, 2, true) == null,
                    "truncated PNG cannot become ready water");
            } catch (IOException malformed) {
                require(true, "truncated PNG explicitly fails decoding");
            }
        }
        Tracking broken = new Tracking(valid, 12);
        expect(IOException.class, () -> WaterPng.averageFirstFrame(broken, 2, 2, true));
        require(!broken.closed, "IO failure never closes caller source");
        broken.close();
        require(broken.closed, "caller can close after failure");
    }

    private static void payloads() throws InterruptedException {
        PreparedBlockLooks t = new PreparedBlockLooks(new Object(), 40);
        int next = 1;
        for (int raw : new int[] { STILL, FLOW }) for (int meta = 0; meta < 16; meta++) {
            int id = t.water(raw, meta, WATER_COLOUR);
            eq(id, next++, "distinct dense water state key");
            eq(t.find(raw, meta), id, "water prepared lookup");
            eq(t.kind[id], Looks.WATER, "never ordinary opaque cube");
            eq(t.tint[id], Looks.TINT_WATER, "water biome selector");
            eq(t.top[id], 0x4080FF, "canonical still RGB in untinted slot");
            eq(t.topTinted[id], 0x4080FF, "canonical still RGB in tinted slot");
            eq(t.side[id], t.top[id], "flow side still colour, not flow sprite");
            eq(t.sideTinted[id], t.top[id], "flow side tint colour");
            near(t.topTintWeight[id], 1, "full water tint regardless texture coverage");
            near(t.sideTintWeight[id], 1, "side water tint");
            eq(t.shape[id], 0, "water has no solid sloped outline");
            eq(t.wetShape[id], -1, "not waterlogged solid");
            require(
                !t.spawnIn[id] && !t.spawnOn[id] && t.emission[id] == 0 && t.constantTint[id] == -1,
                "no light/fixed tint/speculative spawn flags");
            eq(t.water(raw, meta, null), id, "duplicate cannot change published water");
        }
        int high = t.water(Integer.MAX_VALUE, 15, WATER_COLOUR);
        require(high > 0, "full nonnegative raw ID survives");
        eq(t.find(Integer.MAX_VALUE, 15), high, "high raw key no truncation");
        for (int meta : new int[] { -1, 16, 65535 })
            expect(IllegalArgumentException.class, () -> t.water(2, meta, WATER_COLOUR));
        expect(IllegalArgumentException.class, () -> t.water(-1, 0, WATER_COLOUR));
        expect(NullPointerException.class, () -> t.water(2, 0, null));
        for (int channel = 0; channel < 4; channel++)
            for (float bad : new float[] { Float.NaN, Float.POSITIVE_INFINITY, -.01F, 1.01F }) {
                float[] average = WATER_COLOUR.clone();
                average[channel] = bad;
                expect(IllegalArgumentException.class, () -> t.water(2, 0, average));
            }
        expect(IllegalArgumentException.class, () -> t.water(2, 0, new float[] { 1, 1, 1, 0 }));
        eq(t.find(2, 0), -1, "invalid water never consumes key");
        AtomicReference<Throwable> wrong = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                t.water(3, 0, WATER_COLOUR);
            } catch (Throwable failure) {
                wrong.set(failure);
            }
        });
        worker.start();
        worker.join();
        require(wrong.get() instanceof IllegalStateException, "water publication owner gate");
        t.invalidate();
        eq(t.find(STILL, 0), -1, "old resource epoch cannot find water");
        eq(t.water(3, 0, null), -1, "invalidated water cannot allocate");
        eq(t.kind[1], Looks.WATER, "old worker payload remains unchanged");
        PreparedBlockLooks full = new PreparedBlockLooks(new Object(), 1);
        eq(full.water(1, 0, WATER_COLOUR), -1, "capacity keeps pending without opaque fallback");
    }

    private static void captureAndBaker() {
        MapStyle previous = MapStyle.get();
        Facing facing = Projection.facing();
        boolean fast = TileBaker.fast();
        try {
            MapStyle.set(
                new MapStyle(
                    "Water fixture",
                    60,
                    1,
                    1,
                    new float[] { 1, 1, 1 },
                    new float[] { 1, 1, 1 },
                    0,
                    0,
                    false,
                    false,
                    0,
                    50,
                    1,
                    0,
                    0,
                    0));
            Projection.setFacing(Facing.NORTH);
            TileBaker.setFast(true);
            int[] baseline = null;
            for (int raw : new int[] { STILL, FLOW }) for (int meta = 0; meta < 16; meta++) {
                Scene scene = new Scene(raw, meta, 4, 0xFFFFFF, 0x40C080);
                scene.capture();
                int id = scene.table.find(raw, meta);
                eq(scene.snapshot.sectionIds(0)[256], id, "real capture stores prepared WATER id");
                TileBaker baker = TileBaker.forMeshing(scene.key, scene, scene.table, false, null);
                for (boolean top : new boolean[] { true, false }) {
                    baker.albedo(id, 3, 5, top);
                    near(baker.albedoR, linear(0x40) * linear(0x40), "water tint red independent linear RGB");
                    near(baker.albedoG, linear(0x80) * linear(0xC0), "water tint green independent linear RGB");
                    near(baker.albedoB, linear(0xFF) * linear(0x80), "water tint blue independent linear RGB");
                }
                TileSurface surface = TileBaker.surface(scene.key, 1, scene, scene.table, false, 12, 10, 1, 1);
                near(surface.y()[0], 5, "canonical whole liquid cell surface, no metadata-dependent slope");
                require(surface.top()[0], "water surface faces up");
                int[] pixels = scene.pixels();
                if (baseline == null) baseline = pixels;
                else require(
                    Arrays.equals(pixels, baseline),
                    "static/flow metadata all preserve canonical cell/colour result");
                require(pixels[10 * TileImage.BLOCK + 12] >>> 24 == 255, "known water over floor fully covered");
                put(pixels[10 * TileImage.BLOCK + 12]);
            }
            Scene whiteFloor = new Scene(STILL, 0, 1, 0xFFFFFF, 0xFFFFFF);
            Scene blackFloor = new Scene(STILL, 0, 1, 0, 0xFFFFFF);
            Scene deep = new Scene(STILL, 0, 6, 0xFFFFFF, 0xFFFFFF);
            whiteFloor.capture();
            blackFloor.capture();
            deep.capture();
            int white = whiteFloor.pixels()[10 * TileImage.BLOCK + 12];
            int black = blackFloor.pixels()[10 * TileImage.BLOCK + 12];
            int deeper = deep.pixels()[10 * TileImage.BLOCK + 12];
            require(white != black, "water transmits underlying floor, not opaque-cube colour");
            require(white != deeper, "water path depth changes shared tracer blend");
            Scene blackTint = new Scene(STILL, 15, 1, 0xFFFFFF, 0);
            blackTint.capture();
            TileBaker baker = TileBaker.forMeshing(blackTint.key, blackTint, blackTint.table, false, null);
            baker.albedo(blackTint.table.find(STILL, 15), 3, 5, true);
            near(baker.albedoR + baker.albedoG + baker.albedoB, 0, "black water tint not default white");
            Scene unknown = new Scene(STILL, 0, 1, 0xFFFFFF, 0xFFFFFF);
            unknown.unknown = true;
            require(
                ChunkCapture.capture(unknown, unknown, 0, 0)
                    .status() == ChunkCapture.Status.PENDING_APPEARANCE,
                "unknown raw never becomes AIR or WATER");
            unknown.unknown = false;
            unknown.missingTint = true;
            require(
                ChunkCapture.capture(unknown, unknown, 0, 0)
                    .status() == ChunkCapture.Status.PENDING_APPEARANCE,
                "unknown biome water tint remains pending");
            unknown.missingTint = false;
            unknown.stale = true;
            require(
                ChunkCapture.capture(unknown, unknown, 0, 0)
                    .status() == ChunkCapture.Status.STALE_SESSION,
                "stale water result rejected");
        } finally {
            MapStyle.set(previous);
            Projection.setFacing(facing);
            TileBaker.setFast(fast);
        }
    }

    private static final class Scene implements ChunkCapture.Access, ChunkCapture.Chunk, ChunkCapture.Section,
        ChunkCapture.Inputs, VoxelReader.Inputs {

        final Thread owner = Thread.currentThread();
        final Object token = new Object();
        final PreparedBlockLooks table = new PreparedBlockLooks(token, 4);
        final TileKey key = new TileKey(TileKey.SURFACE, 0, 0);
        final int raw, meta, depth, tint;
        ChunkSnapshot snapshot;
        boolean unknown, missingTint, stale;

        Scene(int raw, int meta, int depth, int floor, int tint) {
            this.raw = raw;
            this.meta = meta;
            this.depth = depth;
            this.tint = tint;
            float[] colour = { (floor >> 16 & 255) / 255F, (floor >> 8 & 255) / 255F, (floor & 255) / 255F, 1 };
            table.cube(0, 0, colour, colour, 0);
            table.water(raw, meta, WATER_COLOUR);
        }

        void capture() {
            ChunkCapture.Result result = ChunkCapture.capture(this, this, 0, 0);
            require(result.status() == ChunkCapture.Status.READY, "real water capture ready");
            snapshot = result.snapshot();
        }

        int[] pixels() {
            BitSet patch = new BitSet();
            patch.set(0);
            return TileBaker.bake(key, this, table, patch, false)
                .patches()
                .get(0)
                .mips()[0];
        }

        public boolean onThread() {
            return Thread.currentThread() == owner;
        }

        public boolean current() {
            return !stale;
        }

        public Object session() {
            return token;
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
            return 1;
        }

        public boolean hasSkyLight() {
            return true;
        }

        public ChunkCapture.Section section(int section) {
            return this;
        }

        public int blockId(int cell) {
            int y = cell >> 8;
            return y == 0 ? 0 : y <= depth ? (unknown ? 42 : raw) : AIR;
        }

        public int metadata(int cell) {
            int y = cell >> 8;
            return y > 0 && y <= depth ? meta : 0;
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

        public int lookId(int raw, int meta, int x, int y, int z, int aboveRawId) {
            return raw == AIR ? table.airId() : table.find(raw, meta);
        }

        public ChunkSnapshot.Tints tints(int x, int z, int[] surface) {
            if (missingTint) return null;
            int[] water = new int[256], other = new int[256];
            Arrays.fill(water, tint);
            Arrays.fill(other, 0xFFFFFF);
            return new ChunkSnapshot.Tints(other, other, other, water);
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
            return 16;
        }

        public int topY() {
            return depth + 1;
        }

        public boolean isSeen(int x, int y, int z) {
            return true;
        }
    }

    private static byte[] png(int width, int height, int[] pixels) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try {
            image.setRGB(0, 0, width, height, pixels, 0, width);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            require(ImageIO.write(image, "png", output), "PNG writer exists");
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static byte[] header(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(0x89504E470D0A1A0AL);
        out.writeInt(13);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(body);
        fields.writeInt(0x49484452);
        fields.writeInt(width);
        fields.writeInt(height);
        fields.write(new byte[] { 8, 6, 0, 0, 0 });
        byte[] chunk = body.toByteArray();
        out.write(chunk);
        CRC32 crc = new CRC32();
        crc.update(chunk);
        out.writeInt((int) crc.getValue());
        return bytes.toByteArray();
    }

    private static float[] decode(byte[] bytes, int width, int height, boolean animated) throws IOException {
        Tracking input = new Tracking(bytes, -1);
        try {
            float[] result = WaterPng.averageFirstFrame(input, width, height, animated);
            require(!input.closed, "decoder never closes caller-owned input");
            return result;
        } finally {
            input.close();
        }
    }

    private static float[] average(int[] pixels, int count) {
        double weight = 0, r = 0, g = 0, b = 0;
        int visible = 0;
        for (int i = 0; i < count; i++) {
            int p = pixels[i], alpha = p >>> 24;
            if (alpha < 26) continue;
            // Independent integer-alpha weighting, equivalent without SpriteAverage's /255 factor.
            weight += alpha;
            r += (p >> 16 & 255) * alpha;
            g += (p >> 8 & 255) * alpha;
            b += (p & 255) * alpha;
            visible++;
        }
        return weight == 0 ? new float[4]
            : new float[] { (float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255),
                (float) visible / count };
    }

    private static final class Tracking extends InputStream {

        final byte[] bytes;
        final int failAt;
        int at;
        boolean closed;

        Tracking(byte[] bytes, int failAt) {
            this.bytes = bytes;
            this.failAt = failAt;
        }

        public int read() throws IOException {
            if (failAt >= 0 && at >= failAt) throw new IOException("fixture read failure");
            return at == bytes.length ? -1 : bytes[at++] & 255;
        }

        public void close() {
            closed = true;
        }
    }

    private static double linear(int value) {
        double v = value / 255.0;
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    private static void floats(float[] actual, float[] expected, String why) {
        require(actual != null, why + " ready");
        for (int i = 0; i < 4; i++) near(actual[i], expected[i], why);
    }

    private static void eq(int actual, int expected, String why) {
        require(actual == expected, why);
        put(actual);
    }

    private static void near(double actual, double expected, String why) {
        require(Math.abs(actual - expected) < .000002, why + ": " + actual + " != " + expected);
        put((int) Math.round(actual * 100000));
    }

    private static void require(boolean condition, String why) {
        assertions++;
        if (!condition) throw new AssertionError(why);
    }

    private static void expect(Class<? extends Throwable> type, Throwing action) {
        try {
            action.run();
        } catch (Throwable failure) {
            require(type.isInstance(failure), "wrong exception " + failure);
            return;
        }
        throw new AssertionError("missing " + type.getName());
    }

    private interface Throwing {

        void run() throws Exception;
    }

    private static void put(int value) {
        for (int shift = 24; shift >= 0; shift -= 8) HASH.update((byte) (value >>> shift));
    }

    private static MessageDigest hash() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
