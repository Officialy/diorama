package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Pure preview input and full canonical bake contracts; no native client hooks are invoked. */
public final class TerrainPreviewProbe {

    private static int assertions;
    private static MessageDigest digest;
    private static final TileKey KEY = new TileKey(TileKey.SURFACE, 0, 0);

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        MapStyle oldStyle = MapStyle.get();
        Facing oldFacing = Projection.facing();
        boolean oldFast = TileBaker.fast();
        try {
            MapStyle.set(MapStyle.DEFAULT);
            Projection.setFacing(Facing.NORTH);
            TileBaker.setFast(false);
            keys();
            inputs();
            styles();
            realBake();
            patchBake();
            caveBake();
            partialAppearanceBake();
        } finally {
            MapStyle.set(oldStyle);
            Projection.setFacing(oldFacing);
            TileBaker.setFast(oldFast);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS bounded terrain preview: " + assertions
                + " assertions; numeric identity/bounds/detachment, fail-closed style, full/patch surface and fixed-cut cave canonical ABGR bake; native capture/screen/epoch acceptance UNTESTED");
    }

    private static void keys() {
        eq(
            TerrainPreview.keyFor(0, 0, 0)
                .tileU(),
            0,
            "origin u");
        eq(
            TerrainPreview.keyFor(0, 0, 0)
                .tileV(),
            0,
            "origin v");
        eq(
            TerrainPreview.keyFor(-.01, 0, -.01)
                .tileU(),
            -1,
            "negative u floors, not truncates");
        eq(
            TerrainPreview.keyFor(-.01, 0, -.01)
                .tileV(),
            -1,
            "negative v floors, not truncates");
        eq(
            TerrainPreview.keyFor(128, 0, 0)
                .tileU(),
            1,
            "exact positive tile edge");
        eq(
            TerrainPreview.keyFor(-128, 0, 0)
                .tileU(),
            -1,
            "exact negative tile edge");
        eq(
            TerrainPreview.keyFor(0, 64, 0)
                .tileV(),
            -1,
            "height contributes to projected tile");
        require(
            TerrainPreview.keyFor(0, 0, 0)
                .layer() == TileKey.SURFACE,
            "surface only");
        require(
            TerrainPreview.keyFor(0, 0, 0)
                .lod() == 0,
            "lod zero");
        for (double bad : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MAX_VALUE }) {
            expect(IllegalArgumentException.class, () -> TerrainPreview.keyFor(bad, 0, 0));
            expect(IllegalArgumentException.class, () -> TerrainPreview.keyFor(0, bad, 0));
            expect(IllegalArgumentException.class, () -> TerrainPreview.keyFor(0, 0, bad));
        }
        expect(IllegalArgumentException.class, () -> TerrainPreview.keyFor(Integer.MAX_VALUE, 0, 0));
        expect(IllegalArgumentException.class, () -> TerrainPreview.keyFor(0, 0, Integer.MAX_VALUE));
    }

    private static void inputs() {
        PreparedBlockLooks looks = table();
        Object token = looks.session();
        ChunkSnapshot original = snapshot(looks, -1, 2, false);
        ChunkSnapshot[] slots = { null, original, null };
        TerrainPreview.Work work = TerrainPreview.prepare(looks, token, KEY, slots);
        slots[1] = null;
        require(work.chunk(-1, 2) == original, "caller slot mutation cannot detach ready chunk");
        require(work.chunk(0, 0) == null, "missing remains UNKNOWN");
        require(work.chunk(-1, 1) == null, "no neighbouring coordinate alias");
        require(work.looks() == looks && work.lookDomain() == looks && work.token() == token, "opaque domain identity");
        eq(work.airId(), looks.airId(), "explicit air");
        eq(work.snapshotCount(), 1, "null slots not counted");
        eq(work.minY(), 0, "minY");
        eq(work.maxY(), 256, "maxY exclusive");
        eq(work.topY(), 0, "all air retains empty top");
        require(work.isSeen(Integer.MIN_VALUE, 0, Integer.MAX_VALUE), "no fog-of-war filter");
        require(work.key() == KEY, "exact requested key");
        ChunkSnapshot[] nine = new ChunkSnapshot[9];
        for (int i = 0; i < nine.length; i++) nine[i] = snapshot(looks, i % 3, i / 3, i == 4);
        TerrainPreview.Work bounded = TerrainPreview.prepare(looks, token, KEY, nine);
        eq(bounded.snapshotCount(), 9, "nine ready snapshots allowed");
        ChunkSnapshot[] wide = new ChunkSnapshot[25];
        for (int i = 0; i < wide.length; i++) wide[i] = snapshot(looks, i % 5, i / 5, false);
        TerrainPreview.Work extended = TerrainPreview.prepareExtended(looks, token, KEY, wide);
        eq(extended.snapshotCount(), 25, "extended capture accepts wider snapshot domain");
        if (extended.chunk(4, 4) != wide[24]) throw new AssertionError("extended coordinate lookup");
        eq(bounded.topY(), 1, "top derived from maximum captured height");
        expect(IllegalArgumentException.class, () -> TerrainPreview.prepare(looks, new Object(), KEY, nine));
        expect(IllegalArgumentException.class, () -> TerrainPreview.prepare(looks, token, KEY, new ChunkSnapshot[0]));
        expect(IllegalArgumentException.class, () -> TerrainPreview.prepare(looks, token, KEY, new ChunkSnapshot[9]));
        expect(IllegalArgumentException.class, () -> TerrainPreview.prepare(looks, token, KEY, new ChunkSnapshot[10]));
        expect(
            IllegalArgumentException.class,
            () -> TerrainPreview.prepare(looks, token, KEY, new ChunkSnapshot[] { original, original }));
        PreparedBlockLooks foreign = table();
        expect(
            IllegalArgumentException.class,
            () -> TerrainPreview.prepare(looks, token, KEY, new ChunkSnapshot[] { snapshot(foreign, 0, 0, false) }));
        expect(
            IllegalArgumentException.class,
            () -> prepareBad(looks, 0, 1, 16, Integer.MIN_VALUE, Integer.MIN_VALUE, -1));
        expect(
            IllegalArgumentException.class,
            () -> prepareBad(looks, 1, 0, 16, Integer.MIN_VALUE, Integer.MIN_VALUE, -1));
        expect(
            IllegalArgumentException.class,
            () -> prepareBad(looks, 0, 0, 15, Integer.MIN_VALUE, Integer.MIN_VALUE, -1));
        expect(IllegalArgumentException.class, () -> prepareBad(looks, 0, 0, 16, 256, 256, -1));
        expect(IllegalArgumentException.class, () -> prepareBad(looks, 0, 0, 16, 2, 1, -1));
        expect(IllegalArgumentException.class, () -> prepareBad(looks, 0, 0, 16, 0, 0, 3));
        for (TileKey bad : new TileKey[] { new TileKey(0, 0, 0), new TileKey(-1, 0, 0), new TileKey(261, 0, 0),
            new TileKey(16, 0, 0, 1), new TileKey(TileKey.SURFACE, 0, 0, 1),
            new TileKey(TileKey.SURFACE, Integer.MAX_VALUE, 0), new TileKey(TileKey.SURFACE, 0, Integer.MIN_VALUE) })
            expect(
                IllegalArgumentException.class,
                () -> TerrainPreview.prepare(looks, token, bad, new ChunkSnapshot[] { original }));
        expect(
            IllegalArgumentException.class,
            () -> TerrainPreview
                .prepare(looks, token, KEY, new ChunkSnapshot[] { snapshot(looks, Integer.MAX_VALUE, 0, false) }));
    }

    private static void prepareBad(PreparedBlockLooks looks, int minSection, int air, int count, int surfaceY, int maxY,
        int id) {
        int[] surface = new int[256];
        Arrays.fill(surface, surfaceY);
        int[][] sections = new int[count][];
        if (id >= 0) sections[0] = new int[] { id };
        ChunkSnapshot s = new ChunkSnapshot(
            0,
            0,
            minSection,
            air,
            looks,
            sections,
            new byte[count][],
            new byte[count][],
            true,
            surface,
            maxY,
            0,
            tints());
        TerrainPreview.prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { s });
    }

    private static void styles() {
        PreparedBlockLooks looks = table();
        TerrainPreview.Work work = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, false) });
        Projection.setFacing(Facing.EAST);
        require(!work.stillCurrent(), "rotation revokes work");
        expect(IllegalStateException.class, work::bake);
        Projection.setFacing(Facing.NORTH);
        require(!work.stillCurrent(), "observed invalid work never resurrects");
        work = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, false) });
        TileBaker.setFast(true);
        require(!work.stillCurrent(), "fast mode revokes full-quality work");
        expect(IllegalStateException.class, () -> TerrainPreview.keyFor(0, 0, 0));
        TileBaker.setFast(false);
        work = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, false) });
        MapStyle.set(MapStyle.BASELINE);
        require(!work.stillCurrent(), "style change revokes work");
        expect(
            IllegalStateException.class,
            () -> TerrainPreview
                .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, false) }));
        MapStyle.set(MapStyle.DEFAULT);
        work = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, false) });
        float old = MapStyle.DEFAULT.sunTint()[0];
        try {
            MapStyle.DEFAULT.sunTint()[0] = old + 1;
            require(!work.stillCurrent(), "mutable style tint array cannot silently rewrite prepared work");
        } finally {
            MapStyle.DEFAULT.sunTint()[0] = old;
        }
    }

    private static void realBake() {
        PreparedBlockLooks looks = table();
        TerrainPreview.Work unknown = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 10, 10, false) });
        TileImage empty = unknown.bake();
        require(empty.isFull() && empty.mips()[0].length == 512 * 512, "canonical complete tile size unchanged");
        for (int pixel : empty.mips()[0]) {
            require(pixel == 0, "wholly unknown tile remains exactly transparent");
        }
        TerrainPreview.Work floor = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, true) });
        TileImage image = floor.bake();
        require(
            image.isFull() && image.patches()
                .isEmpty(),
            "real bake returns full image not patches");
        require(
            image.key()
                .equals(KEY),
            "image belongs to requested tile");
        eq(image.mips()[0].length, Projection.TILE_PIXELS * Projection.TILE_PIXELS, "base mip extent");
        int known = image.mips()[0][24 * 512 + 32];
        eq(known >>> 24, 255, "interior projected floor pixel is opaque");
        require((known & 255) > 0 && (known & 0xFFFF00) == 0, "red-only material is ABGR red, not ARGB blue");
        eq(image.mips()[0][24 * 512 + 400], 0, "missing distant column is transparent, not invented terrain");
        int visible = 0;
        for (int pixel : image.mips()[0]) {
            if ((pixel >>> 24) != 0) visible++;
            put(pixel);
        }
        require(visible > 0 && visible < image.mips()[0].length, "bounded capture produces partial visible coverage");
        require(floor.stillCurrent(), "bake does not alter global projection/style");
    }

    private static void patchBake() {
        PreparedBlockLooks looks = table();
        ChunkSnapshot floor = snapshot(looks, 0, 0, true);
        TerrainPreview.Work old = TerrainPreview.prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { floor });
        TerrainPreview.Work next = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { floor, snapshot(looks, 0, 1, true) });
        int edge = Projection.TILE_PIXELS;
        int block = TileImage.BLOCK;
        int stride = edge / block;
        int[] previous = old.bakeDetail(null);
        int[] saved = previous.clone();
        int[] full = next.bakeDetail(previous);
        BitSet patches = new BitSet();
        patches.set(stride);
        patches.set(stride + 1);
        patches.set(stride * stride - 1);
        int[] partial = next.bakeDetailPatches(previous, patches);
        int changed = 0, pending = 0;
        for (int y = 0; y < edge; y++) for (int x = 0; x < edge; x++) {
            int i = y * edge + x;
            boolean selected = patches.get((y / block) * stride + x / block);
            if (full[i] != previous[i]) {
                if (selected) changed++;
                else pending++;
            }
            require(
                partial[i] == (selected ? full[i] : previous[i]),
                "patch interior matches full; exterior unchanged");
        }
        require(changed > 0 && pending > 0, "fixture changes terrain inside and outside the selected blocks");
        require(partial != previous && Arrays.equals(previous, saved), "patch never mutates previous pixels");
        patches.flip(0, stride * stride);
        require(
            Arrays.equals(next.bakeDetailPatches(partial, patches), full),
            "assembled patches equal full HUD raster");

        // A floor at the capture edge yields 1..3 known samples. Fresh partial coverage must
        // replace opaque history, while a canonical zero outside the capture retains history.
        int[] synthetic = new int[edge * edge];
        Arrays.fill(synthetic, 0xFF465768);
        int[] canonical = next.bake()
            .mips()[0];
        patches.set(0, stride * stride);
        int[] merged = next.bakeDetailPatches(synthetic, patches);
        int fractional = 0;
        for (int i = 0; i < merged.length; i++) {
            int alpha = canonical[i] >>> 24;
            if (alpha > 0 && alpha < 255) fractional++;
            require(merged[i] == (alpha == 0 ? synthetic[i] : canonical[i]), "HUD prefers all known fresh samples");
        }
        require(fractional > 0, "fixture exercises a partial-coverage overwrite");

        TerrainPreview.Work empty = TerrainPreview
            .prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { snapshot(looks, 0, 0, false) });
        BitSet first = new BitSet();
        first.set(0);
        int[] cleared = empty.bakeDetailPatches(synthetic, first);
        int[] emptyFull = empty.bakeDetail(synthetic);
        require(cleared[24 * edge + 20] == 0, "last terrain removed: ready empty footprint now transparent");
        require(emptyFull[24 * edge + 20] == 0, "full refresh also makes ready empty footprint transparent");
        require(cleared[24 * edge + 32] == synthetic[24 * edge + 32], "empty refresh respects the patch boundary");
        require(emptyFull[24 * edge + 400] == synthetic[24 * edge + 400], "missing footprint still retains history");
        require(empty.bakeDetail(null) == null, "uninitialized empty raster stays absent");
        expect(IllegalArgumentException.class, () -> next.bakeDetailPatches(synthetic, new BitSet()));
        expect(IllegalArgumentException.class, () -> next.bakeDetailPatches(new int[1], first));
        first.set(stride * stride);
        expect(IllegalArgumentException.class, () -> next.bakeDetailPatches(synthetic, first));
        for (int pixel : full) put(pixel);
        for (int pixel : cleared) put(pixel);
    }

    private static void caveBake() {
        PreparedBlockLooks looks = table();
        looks.cube(2, 0, new float[] { 0, 0, 1, 1 }, new float[] { 0, 0, 1, 1 }, 0);
        ChunkSnapshot[] chamber = { caveChamber(looks, true) };
        TileKey low = new TileKey(8, 0, 0);
        TerrainPreview.Work cut = TerrainPreview.prepare(looks, looks.session(), low, chamber);
        TerrainPreview.Work over = TerrainPreview.prepare(looks, looks.session(), new TileKey(10, 0, 0), chamber);
        TileKey high = new TileKey(260, 0, 0);
        TerrainPreview.Work clamped = TerrainPreview.prepare(looks, looks.session(), high, chamber);
        eq(cut.topY(), 8, "exclusive cave ceiling is independent of captured surface top");
        eq(over.topY(), 10, "higher cave cut has its own ceiling");
        require(clamped.key() == high, "clamping the ray ceiling does not alias the requested key");
        eq(clamped.topY(), 256, "player-follow cut above the world clamps to the exclusive storage top");
        eq(TerrainPreview.caveCeiling(new TileKey(256, 0, 0)), 256, "section span end is a valid cut");

        int edge = Projection.TILE_PIXELS;
        int[] pixels = cut.bakeDetail(null);
        require(pixels != null && pixels.length == edge * edge, "cave bake returns a canonical full raster");
        // The center of this pixel lands inside the red floor at y=0 with the blue slab
        // excluded at y=8; the higher cut reaches the same slab through open space from above.
        int probe = 16 * edge + 32;
        int floor = pixels[probe];
        int roof = over.bake()
            .mips()[0][probe];
        eq(floor >>> 24, 255, "cave interior floor opaque at the cut");
        eq(roof >>> 24, 255, "cave interior roof opaque above the cut");
        require((floor & 255) > (floor >>> 16 & 255), "exclusive cut reveals red floor below blue roof");
        require((roof >>> 16 & 255) > (roof & 255), "higher cut hits blue roof");
        TerrainPreview.Work open = TerrainPreview
            .prepare(looks, looks.session(), low, new ChunkSnapshot[] { caveChamber(looks, false) });
        eq(
            open.bake()
                .mips()[0][probe],
            floor,
            "roof at the ceiling does not affect detached floor sample");
        int distant = 16 * edge + 400;
        eq(pixels[distant], 0, "unloaded cave footprint remains transparent, not air or rock");
        int[] history = new int[pixels.length];
        Arrays.fill(history, 0xFF465768);
        int[] merged = cut.bakeDetail(history);
        eq(merged[probe], floor, "known cave sample replaces memory history");
        eq(merged[distant], history[distant], "missing cave sample retains memory history");
        eq(history[probe], 0xFF465768, "cave refresh never changes caller history in place");
        BitSet patch = new BitSet();
        patch.set(0);
        expect(IllegalArgumentException.class, () -> cut.bakeDetailPatches(history, patch));
        for (int pixel : pixels) put(pixel);
    }

    private static ChunkSnapshot caveChamber(PreparedBlockLooks looks, boolean roof) {
        int[][] sections = new int[16][];
        sections[0] = new int[4096];
        Arrays.fill(sections[0], 0, 256, 1);
        if (roof) Arrays.fill(sections[0], 8 * 256, 9 * 256, 2);
        int[] surface = new int[256];
        Arrays.fill(surface, roof ? 8 : 0);
        byte[][] blockLight = new byte[16][];
        blockLight[0] = new byte[4096];
        Arrays.fill(blockLight[0], (byte) 13);
        return new ChunkSnapshot(
            0,
            0,
            0,
            looks.airId(),
            looks,
            sections,
            blockLight,
            new byte[16][],
            false,
            surface,
            roof ? 8 : 0,
            0,
            tints());
    }

    private static void partialAppearanceBake() {
        PreparedBlockLooks looks = table();
        int[][] sections = new int[16][];
        sections[0] = new int[4096];
        Arrays.fill(sections[0], 0, 256, 1);
        int[] surface = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 4; x++) {
            sections[0][256 + z * 16 + x] = ChunkSnapshot.UNKNOWN_LOOK;
            surface[z * 16 + x] = 1;
        }
        ChunkSnapshot mixed = new ChunkSnapshot(
            0,
            0,
            0,
            looks.airId(),
            looks,
            sections,
            new byte[16][],
            new byte[16][],
            true,
            surface,
            1,
            0,
            tints());
        TerrainPreview.Work work = TerrainPreview.prepare(looks, looks.session(), KEY, new ChunkSnapshot[] { mixed });
        VoxelReader reader = new VoxelReader(work, looks);
        eq(reader.state(1, 1, 4), VoxelReader.UNKNOWN, "unavailable appearance remains unknown to camera rays");
        eq(reader.solid(1, 1, 4), VoxelReader.UNKNOWN, "unknown remains unknown to solid-mask rays");
        eq(reader.solid(8, 1, 4), 0, "adjacent air remains distinct from unknown");
        eq(reader.solid(8, 0, 4), 1, "known floor remains solid");
        int[] pixels = work.bake()
            .mips()[0];
        int row = 16 * Projection.TILE_PIXELS;
        eq(pixels[row + 6], 0, "ray hitting unknown stays transparent rather than exposing the floor beneath");
        eq(pixels[row + 32] >>> 24, 255, "known terrain in the same chunk stays visible");
    }

    private static PreparedBlockLooks table() {
        PreparedBlockLooks looks = new PreparedBlockLooks(new Object(), 4);
        looks.cube(1, 0, new float[] { 1, 0, 0, 1 }, new float[] { 1, 0, 0, 1 }, 0);
        return looks;
    }

    private static ChunkSnapshot snapshot(PreparedBlockLooks looks, int x, int z, boolean floor) {
        int[][] sections = new int[16][];
        int[] surface = new int[256];
        Arrays.fill(surface, floor ? 0 : Integer.MIN_VALUE);
        if (floor) {
            sections[0] = new int[4096];
            Arrays.fill(sections[0], 0, 256, 1);
        }
        ChunkSnapshot snapshot = new ChunkSnapshot(
            x,
            z,
            0,
            looks.airId(),
            looks,
            sections,
            new byte[16][],
            new byte[16][],
            true,
            surface,
            floor ? 0 : Integer.MIN_VALUE,
            0,
            tints());
        // Constructor source arrays are detached too; preview never acquires a live storage view.
        Arrays.fill(surface, 255);
        if (floor) Arrays.fill(sections[0], 0);
        return snapshot;
    }

    private static ChunkSnapshot.Tints tints() {
        int[] colors = new int[256];
        Arrays.fill(colors, 0xFFFFFF);
        return new ChunkSnapshot.Tints(colors, colors, colors, colors);
    }

    private static void eq(int actual, int expected, String message) {
        require(actual == expected, message + ": " + actual + " != " + expected);
        put(actual);
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            require(type.isInstance(t), "wrong exception " + t);
            return;
        }
        throw new AssertionError("Missing " + type.getName());
    }

    private static void put(int value) {
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }
}
