package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;

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
                + " assertions; numeric identity/bounds/detachment, fail-closed style, full canonical ABGR bake; native capture/screen/epoch acceptance UNTESTED");
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
        for (TileKey bad : new TileKey[] { new TileKey(0, 0, 0), new TileKey(TileKey.SURFACE, 0, 0, 1),
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
