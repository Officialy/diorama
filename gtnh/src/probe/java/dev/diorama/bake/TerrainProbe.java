package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;

/** Original CPU baker against private numeric snapshots, not Minecraft world capture. */
public final class TerrainProbe {

    private static final int AIR = 7;
    private static final int RED = 0;
    private static final int WHITE = 1;
    private static final int WATER = 2;
    private static final int GLASS = 3;
    private static final int SLAB = 4;
    private static final int BLUE = 5;
    private static final int GRASS = 6;
    private static final TileKey SURFACE = new TileKey(TileKey.SURFACE, 0, 0);
    private static final MessageDigest HASH = digest();
    private static int assertions;

    public static void main(String[] args) {
        MapStyle previous = MapStyle.get();
        Facing facing = Projection.facing();
        boolean fast = TileBaker.fast();
        try {
            MapStyle.set(
                new MapStyle(
                    "Numeric fixture",
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
            TestLooks looks = new TestLooks();
            ownershipAndDomains(looks);
            verticalSpanAndSkyFallback(looks);
            fullAndPatches(looks);
            materialsAndTints(looks);
            unknownAndSaved(looks);
            caveAndRoof(looks);
            StringBuilder hex = new StringBuilder();
            for (byte b : HASH.digest()) {
                hex.append(Character.forDigit((b >>> 4) & 15, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            System.out.println("FIXTURE-SHA256=" + hex);
            System.out.println(
                "PASS CPU terrain: " + assertions
                    + " assertions; full/patch/mips/coverage, material/tint, snapshot ownership/domain, missing/saved, cave/roof; capture/game/GL/performance UNTESTED");
        } finally {
            TileBaker.setFast(fast);
            MapStyle.set(previous);
            Projection.setFacing(facing);
        }
    }

    private static void ownershipAndDomains(TestLooks looks) {
        int[][] ids = { new int[4096] };
        Arrays.fill(ids[0], AIR);
        ids[0][0] = RED;
        byte[][] block = { new byte[4096] };
        byte[][] sky = { new byte[4096] };
        block[0][1] = 9;
        sky[0][1] = 11;
        int[] surface = filled(256, 0);
        int[] grass = filled(256, 0x12AB34);
        int[] foliage = filled(256, 0x234567);
        int[] dry = filled(256, 0x345678);
        int[] water = filled(256, 0x456789);
        ChunkSnapshot snapshot = new ChunkSnapshot(
            -1,
            2,
            0,
            AIR,
            looks,
            ids,
            block,
            sky,
            true,
            surface,
            0,
            0x330808,
            new ChunkSnapshot.Tints(grass, foliage, dry, water));
        ids[0][0] = AIR;
        block[0][1] = 0;
        sky[0][1] = 0;
        surface[0] = 15;
        Arrays.fill(grass, 0);
        Arrays.fill(foliage, 0);
        Arrays.fill(dry, 0);
        Arrays.fill(water, 0);
        ids[0] = null;
        Source source = new Source(looks, 0);
        source.forced = snapshot;
        VoxelReader reader = new VoxelReader(source, looks, true);
        require(reader.state(-16, 0, 32) == RED, "snapshot must own dense IDs and outer section table");
        require(reader.state(-15, 0, 32) == AIR, "known air remains nonzero ID");
        require(
            reader.blockLight(-15, 0, 32) == 9 && reader.skyLight(-15, 0, 32) == 11,
            "snapshot owns captured lights");
        require(snapshot.surface(0, 0) == 0 && snapshot.maxY == 0, "snapshot owns captured heights");
        ChunkSnapshot.Tints t = snapshot.bakeTints();
        require(
            t.grass()[0] == 0x12AB34 && t.foliage()[0] == 0x234567
                && t.dryFoliage()[0] == 0x345678
                && t.water()[0] == 0x456789,
            "snapshot owns four tint inputs");
        require(reader.state(-16, -1, 32) == AIR && reader.state(-16, 16, 32) == AIR, "world bounds use source air");
        require(reader.blockLight(-16, -1, 32) == 0 && reader.skyLight(-16, 16, 32) == 15, "light world bounds");
        require(snapshot.topBelow(16) == 1, "topBelow returns one above captured top block");
        expectRejected(() -> new VoxelReader(source, new TestLooks(), true), "source look domain mismatch");
        Source wrongSnapshot = new Source(looks, 0);
        wrongSnapshot.forced = new ChunkSnapshot(
            0,
            0,
            0,
            AIR,
            new Object(),
            new int[][] { new int[] { RED } },
            new byte[1][],
            new byte[1][],
            true,
            filled(256, 15),
            15,
            0,
            tints(0xFFFFFF));
        expectRejected(
            () -> new VoxelReader(wrongSnapshot, looks, true).state(0, 0, 0),
            "snapshot look domain mismatch");
        Source wrongAir = new Source(looks, 0);
        wrongAir.forced = new ChunkSnapshot(
            0,
            0,
            0,
            AIR + 1,
            looks,
            new int[][] { new int[] { RED } },
            new byte[1][],
            new byte[1][],
            true,
            filled(256, 15),
            15,
            0,
            tints(0xFFFFFF));
        expectRejected(() -> new VoxelReader(wrongAir, looks, true).state(0, 0, 0), "snapshot air domain mismatch");
        put(reader.state(-16, 0, 32));
        put(t.grass()[0]);
    }

    private static void verticalSpanAndSkyFallback(TestLooks looks) {
        Source shifted = new Source(looks, 0);
        shifted.forced = new ChunkSnapshot(
            0,
            0,
            1,
            AIR,
            looks,
            new int[1][],
            new byte[1][],
            new byte[1][],
            true,
            filled(256, Integer.MIN_VALUE),
            Integer.MIN_VALUE,
            0,
            tints(0xFFFFFF));
        expectRejected(
            () -> new VoxelReader(shifted, looks, true).blockLight(0, 0, 0),
            "shifted snapshot section origin rejected before light index");
        Source shortSpan = new Source(looks, 0);
        shortSpan.worldMax = 32;
        shortSpan.forced = new ChunkSnapshot(
            0,
            0,
            0,
            AIR,
            looks,
            new int[1][],
            new byte[1][],
            new byte[1][],
            true,
            filled(256, Integer.MIN_VALUE),
            Integer.MIN_VALUE,
            0,
            tints(0xFFFFFF));
        expectRejected(
            () -> new VoxelReader(shortSpan, looks, true).skyLight(0, 20, 0),
            "short snapshot section span rejected before light index");
        byte[] near = new byte[4096];
        near[5 * 16 + 3] = 9;
        near[2 * 256 + 5 * 16 + 3] = 2;
        byte[] far = new byte[4096];
        Arrays.fill(far, (byte) 12);
        Source sky = skySource(looks, true, new byte[][] { null, near, far });
        VoxelReader reader = new VoxelReader(sky, looks, true);
        require(reader.skyLight(3, 7, 5) == 9, "absent sky section reads nearest stored above at bottom y zero");
        require(reader.skyLight(3, 18, 5) == 2, "present sky section retains actual local y");
        require(reader.skyLight(3, 34, 5) == 12, "upper present sky section");
        require(reader.blockLight(3, 7, 5) == 0, "absent block light is zero without scanning above");
        Source skipped = skySource(looks, true, new byte[][] { null, null, far });
        require(
            new VoxelReader(skipped, looks, true).skyLight(3, 7, 5) == 12,
            "sky fallback skips multiple absent sections");
        Source noStored = skySource(looks, true, new byte[3][]);
        require(
            new VoxelReader(noStored, looks, true).skyLight(3, 7, 5) == 15,
            "sky fallback without stored above is full sky");
        Source noSky = skySource(looks, false, new byte[][] { null, near, far });
        require(
            new VoxelReader(noSky, looks, true).skyLight(3, 7, 5) == 0,
            "dimension without sky ignores stored sky input");
        put(reader.skyLight(3, 7, 5));
        put(reader.skyLight(3, 18, 5));
    }

    private static Source skySource(TestLooks looks, boolean hasSky, byte[][] skyLayers) {
        Source source = new Source(looks, 0);
        source.worldMax = 48;
        source.forced = new ChunkSnapshot(
            0,
            0,
            0,
            AIR,
            looks,
            new int[3][],
            new byte[3][],
            skyLayers,
            hasSky,
            filled(256, Integer.MIN_VALUE),
            Integer.MIN_VALUE,
            0,
            tints(0xFFFFFF));
        return source;
    }

    private static void fullAndPatches(TestLooks looks) {
        Source flat = new Source(looks, 9);
        TileImage full = TileBaker.bake(SURFACE, flat, looks, null, false);
        require(
            full.isFull() && full.patches()
                .isEmpty(),
            "full image contract");
        require(
            full.mips().length == Mipmaps.LEVELS
                && full.mips()[0].length == Projection.TILE_PIXELS * Projection.TILE_PIXELS,
            "full mip dimensions");
        boolean sawRed = false;
        boolean sawBlue = false;
        for (int pixel : full.mips()[0]) {
            require(pixel >>> 24 == 255, "known flat terrain fully covered");
            boolean red = (pixel & 255) > 0 && (pixel & 0xFFFF00) == 0;
            boolean blue = ((pixel >>> 16) & 255) > 0 && (pixel & 0xFFFF) == 0;
            require(red || blue, "pure red/blue terrain preserves ABGR channels");
            sawRed |= red;
            sawBlue |= blue;
        }
        require(sawRed && sawBlue, "spatially varying baseline prevents constant-image patch comparisons");
        for (byte v : full.coverage()) require((v & 255) == 255, "full known coverage cells");
        verifyMips(full.mips(), Projection.TILE_PIXELS);
        BitSet selected = new BitSet();
        selected.set(0);
        selected.set(3 + 4 * 16);
        selected.set(15 + 15 * 16);
        TileImage partial = TileBaker.bake(SURFACE, flat, looks, selected, false);
        require(
            !partial.isFull() && partial.patches()
                .size() == 3 && partial.mips() == null && partial.coverage() == null,
            "partial image contract");
        for (TileImage.Patch patch : partial.patches()) {
            for (int level = 0; level < Mipmaps.LEVELS; level++) {
                int edge = TileImage.BLOCK >> level;
                int stride = Projection.TILE_PIXELS >> level;
                int ox = patch.blockX() * edge;
                int oy = patch.blockY() * edge;
                for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) require(
                    patch.mips()[level][y * edge + x] == full.mips()[level][(oy + y) * stride + ox + x],
                    "incremental patch equals aligned full tile at every mip");
            }
            int coverageEdge = (int) Math.sqrt(patch.coverage().length);
            for (int y = 0; y < coverageEdge; y++) for (int x = 0; x < coverageEdge; x++) require(
                patch.coverage()[y * coverageEdge + x]
                    == full.coverage()[(patch.blockY() * coverageEdge + y) * Projection.COVERAGE_CELLS
                        + patch.blockX() * coverageEdge
                        + x],
                "patch coverage equals full");
            int lightEdge = TileImage.BLOCK / BlockLight.SCALE;
            for (int y = 0; y < lightEdge; y++) for (int x = 0; x < lightEdge; x++) require(
                patch.blockLight()[y * lightEdge + x] == full
                    .blockLight()[(patch.blockY() * lightEdge + y) * BlockLight.EDGE + patch.blockX() * lightEdge + x],
                "patch block light equals full");
            trace(patch.mips()[0]);
        }
        int[] empty = TileBaker.bake(SURFACE, new Source(looks, 1), looks, onePatch(), false)
            .patches()
            .get(0)
            .mips()[0];
        require(
            Arrays.stream(empty)
                .allMatch(p -> p >>> 24 == 255),
            "known empty world keeps known-background coverage");
        require((empty[0] & 255) < ((empty[0] >>> 16) & 255), "known empty background is cool rather than red terrain");
        trace(full.mips()[Mipmaps.LEVELS - 1]);
    }

    private static void materialsAndTints(TestLooks looks) {
        int[] plain = patch(new Source(looks, 0), looks, SURFACE, false).mips()[0];
        int[] water = patch(new Source(looks, 2), looks, SURFACE, false).mips()[0];
        int[] glass = patch(new Source(looks, 3), looks, SURFACE, false).mips()[0];
        int[] slab = patch(new Source(looks, 4), looks, SURFACE, false).mips()[0];
        int[] cube = patch(new Source(looks, 5), looks, SURFACE, false).mips()[0];
        require(!Arrays.equals(plain, water), "water changes terrain color");
        require(!Arrays.equals(plain, glass), "glass transmission changes terrain color");
        require(!Arrays.equals(slab, cube), "partial slab shape differs from full blue cube");
        for (int[] pixels : new int[][] { water, glass, slab, cube }) {
            require(
                Arrays.stream(pixels)
                    .allMatch(p -> p >>> 24 == 255),
                "material above known floor keeps coverage");
            trace(pixels);
        }
        Source green = new Source(looks, 6);
        green.tintRGB = 0x00FF00;
        Source red = new Source(looks, 6);
        red.tintRGB = 0xFF0000;
        int[] gp = patch(green, looks, SURFACE, false).mips()[0];
        int[] rp = patch(red, looks, SURFACE, false).mips()[0];
        require(!Arrays.equals(gp, rp), "captured biome tints reach original tracer");
        for (int i = 0; i < gp.length; i++) {
            require(((gp[i] >>> 8) & 255) > (gp[i] & 255), "grass tint is green");
            require((rp[i] & 255) > ((rp[i] >>> 8) & 255), "grass tint is red");
        }
        trace(gp);
        trace(rp);
    }

    private static void unknownAndSaved(TestLooks looks) {
        Source absent = new Source(looks, 0);
        absent.missing = true;
        VoxelReader missingReader = new VoxelReader(absent, looks, true);
        VoxelReader emptyReader = new VoxelReader(new Source(looks, 1), looks, true);
        require(
            missingReader.state(0, 2, 0) == VoxelReader.UNKNOWN && emptyReader.state(0, 2, 0) == AIR,
            "missing chunk must not become known air");
        TileImage.Patch fresh = patch(absent, looks, SURFACE, false);
        require(
            Arrays.stream(fresh.mips()[0])
                .allMatch(p -> p == 0),
            "fully absent world is transparent");
        for (byte b : fresh.coverage()) require(b == 0, "fully absent world coverage is zero");
        int[] saved = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
        for (int i = 0; i < saved.length; i++) saved[i] = 0xFF000000 | (i * 0x10203 & 0xFFFFFF);
        byte[] savedLight = new byte[BlockLight.EDGE * BlockLight.EDGE];
        Arrays.fill(savedLight, (byte) 119);
        TileImage.Patch merged = TileBaker
            .bake(SURFACE, absent, looks, onePatch(), false, saved, savedLight, null, null)
            .patches()
            .get(0);
        for (int y = 0; y < TileImage.BLOCK; y++) for (int x = 0; x < TileImage.BLOCK; x++) require(
            merged.mips()[0][y * TileImage.BLOCK + x] == saved[y * Projection.TILE_PIXELS + x],
            "unknown bake preserves saved pixels exactly");
        for (byte b : merged.blockLight()) require((b & 255) == 119, "unknown bake preserves saved block light");
        for (byte b : merged.coverage()) require((b & 255) == 255, "saved known terrain restores coverage");
        verifyMips(merged.mips(), TileImage.BLOCK);
        trace(merged.mips()[0]);
    }

    private static void caveAndRoof(TestLooks looks) {
        TileKey cave = new TileKey(12, 0, 0);
        Source dark = new Source(looks, 7);
        Source bright = new Source(looks, 7);
        bright.light = 15;
        TileImage.Patch low = patch(dark, looks, cave, false);
        TileImage.Patch high = patch(bright, looks, cave, false);
        require(low.blockLight() == null && high.blockLight() == null, "cave image has no surface block-light layer");
        require(luma(high.mips()[0]) > luma(low.mips()[0]), "captured cave light increases brightness");
        bright.seen = false;
        TileImage.Patch unseen = patch(bright, looks, cave, true);
        require(
            Arrays.stream(unseen.mips()[0])
                .allMatch(p -> p == 0),
            "unseen cave terrain hidden by fog-of-war");
        Source roof = new Source(looks, 8);
        roof.roofY = 12;
        roof.light = 15;
        TileImage.Patch lifted = patch(roof, looks, SURFACE, false);
        require(
            Arrays.stream(lifted.mips()[0])
                .anyMatch(p -> p >>> 24 != 0),
            "roof view reaches prepared floor under lifted ceiling");
        require(lifted.blockLight() == null, "roof view uses lit terrain contract");
        trace(low.mips()[0]);
        trace(high.mips()[0]);
        trace(lifted.mips()[0]);
    }

    private static TileImage.Patch patch(Source source, TestLooks looks, TileKey key, boolean fog) {
        return TileBaker.bake(key, source, looks, onePatch(), fog)
            .patches()
            .get(0);
    }

    private static BitSet onePatch() {
        BitSet bits = new BitSet();
        bits.set(0);
        return bits;
    }

    private static ChunkSnapshot.Tints tints(int rgb) {
        return new ChunkSnapshot.Tints(filled(256, rgb), filled(256, rgb), filled(256, rgb), filled(256, 0x2040FF));
    }

    private static int[] filled(int size, int value) {
        int[] out = new int[size];
        Arrays.fill(out, value);
        return out;
    }

    private static void verifyMips(int[][] mips, int size) {
        for (int level = 1; level < Mipmaps.LEVELS; level++) {
            int edge = size >> level;
            int stride = edge * 2;
            require(mips[level].length == edge * edge, "mip dimensions");
            for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) {
                int i = y * 2 * stride + x * 2;
                int[] p = { mips[level - 1][i], mips[level - 1][i + 1], mips[level - 1][i + stride],
                    mips[level - 1][i + stride + 1] };
                long alpha = 0, red = 0, green = 0, blue = 0;
                for (int c : p) {
                    int a = c >>> 24;
                    alpha += a;
                    red += (c & 255) * a;
                    green += ((c >>> 8) & 255) * a;
                    blue += ((c >>> 16) & 255) * a;
                }
                int expected = alpha == 0 ? 0
                    : (int) ((alpha / 4) << 24 | (blue / alpha) << 16 | (green / alpha) << 8 | red / alpha);
                require(mips[level][y * edge + x] == expected, "independent long-sum alpha-weighted mip oracle");
            }
        }
    }

    private static long luma(int[] pixels) {
        long sum = 0;
        for (int p : pixels) sum += (p & 255) + ((p >>> 8) & 255) + ((p >>> 16) & 255);
        return sum;
    }

    private static void trace(int[] pixels) {
        for (int p : pixels) put(p);
    }

    private static void put(int value) {
        HASH.update((byte) (value >>> 24));
        HASH.update((byte) (value >>> 16));
        HASH.update((byte) (value >>> 8));
        HASH.update((byte) value);
    }

    private static void require(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    private static void expectRejected(Runnable action, String message) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final class TestLooks extends Looks {

        TestLooks() {
            super(8, 8);
            for (int i = 0; i < 8; i++) ensure(i);
        }

        @Override
        protected void make(int id) {
            constantTint[id] = -1;
            top[id] = side[id] = id == TerrainProbe.RED ? 0xFF0000
                : id == TerrainProbe.SLAB || id == TerrainProbe.BLUE ? 0x0000FF
                    : id == TerrainProbe.GLASS || id == TerrainProbe.WATER ? 0x2040FF : 0xFFFFFF;
            if (id == TerrainProbe.SLAB) shape[id] = internShape(new float[] { 0, 0, 0, 1, 0.5F, 1 });
            if (id == TerrainProbe.GRASS) {
                top[id] = side[id] = 0;
                topTinted[id] = sideTinted[id] = 0xFFFFFF;
                topTintWeight[id] = sideTintWeight[id] = 1;
                tint[id] = Looks.TINT_GRASS;
            }
            if (id == TerrainProbe.WATER) tint[id] = Looks.TINT_WATER;
            publish(
                id,
                id == TerrainProbe.AIR ? Looks.AIR
                    : id == TerrainProbe.WATER ? Looks.WATER : id == TerrainProbe.GLASS ? Looks.GLASS : Looks.SOLID);
        }
    }

    private static final class Source implements VoxelReader.Inputs {

        private final TestLooks looks;
        private final int mode;
        private final Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        private boolean missing;
        private boolean seen = true;
        private int light;
        private int worldMax = 16;
        private int tintRGB = 0xFFFFFF;
        private int roofY = NO_ROOF;
        private ChunkSnapshot forced;

        Source(TestLooks looks, int mode) {
            this.looks = looks;
            this.mode = mode;
        }

        @Override
        public int airId() {
            return AIR;
        }

        @Override
        public Object lookDomain() {
            return looks;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return worldMax;
        }

        @Override
        public int topY() {
            return mode == 1 ? 1 : mode == 8 ? 12 : mode == 2 || mode == 3 ? 7 : mode == 4 || mode == 5 ? 5 : 4;
        }

        @Override
        public int roof() {
            return roofY;
        }

        @Override
        public boolean isSeen(int x, int y, int z) {
            return seen;
        }

        @Override
        public int topBelow(int cx, int cz, int ceiling) {
            return mode == 1 ? Integer.MIN_VALUE : Math.min(topY(), ceiling);
        }

        @Override
        public ChunkSnapshot chunk(int cx, int cz) {
            if (forced != null) return forced;
            if (missing) return null;
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            ChunkSnapshot old = chunks.get(key);
            if (old != null) return old;
            int[] ids = filled(4096, AIR);
            int[] surface = filled(256, Integer.MIN_VALUE);
            int maximum = Integer.MIN_VALUE;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int id = AIR;
                if (mode != 1 && y < 4)
                    id = mode == 6 ? GRASS : mode == 7 ? WHITE : mode == 9 && Math.floorMod(cx, 3) == 1 ? BLUE : RED;
                if ((mode == 2 || mode == 3) && y >= 4 && y < 7) id = mode == 2 ? WATER : GLASS;
                if ((mode == 4 || mode == 5) && y == 4
                    && Math.floorMod(cx * 16 + x, 4) < 2
                    && Math.floorMod(cz * 16 + z, 4) < 2) id = mode == 4 ? SLAB : BLUE;
                if (mode == 8 && y >= 10 && y < 12) id = RED;
                ids[y * 256 + z * 16 + x] = id;
                if (id != AIR) {
                    surface[z * 16 + x] = y;
                    maximum = Math.max(maximum, y);
                }
            }
            byte[] captured = new byte[4096];
            Arrays.fill(captured, (byte) light);
            ChunkSnapshot made = new ChunkSnapshot(
                cx,
                cz,
                0,
                AIR,
                looks,
                new int[][] { ids },
                new byte[][] { captured },
                new byte[][] { captured },
                true,
                surface,
                maximum,
                0x330808,
                tints(tintRGB));
            chunks.put(key, made);
            return made;
        }
    }
}
