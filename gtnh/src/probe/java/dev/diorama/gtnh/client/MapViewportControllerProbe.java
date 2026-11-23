package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.client.MapViewportController.Tile;

/** Map camera and tile intersection regressions, without Minecraft. */
public final class MapViewportControllerProbe {

    private static final int UNITS = Projection.TILE_UNITS;
    private static int assertions;
    private static MessageDigest digest;

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        checkCamera();
        checkClipping();
        checkOverview();
        checkFixedMinimapParity();
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS map viewport: " + assertions
                + " assertions; pan, anchored zoom, recenter, LOD policy, tile clipping and minimap parity");
    }

    private static void checkCamera() {
        MapViewportController view = new MapViewportController(-130.25, 65.5, 2, 0.5, 4);
        view.resize(17, 29, 301, 177);
        close(view.screenX(-130.25), 167.5, "odd-width screen center");
        close(view.screenY(65.5), 117.5, "odd-height screen center");
        close(view.projectedU(view.screenX(-111.25)), -111.25, "U inverse");
        close(view.projectedV(view.screenY(25.5)), 25.5, "V inverse");

        double cursorX = 213.25, cursorY = 46.75;
        double grabbedU = view.projectedU(cursorX), grabbedV = view.projectedV(cursorY);
        view.panBy(71.5, -42.25);
        close(view.screenX(grabbedU), cursorX + 71.5, "drag moves ground with mouse horizontally");
        close(view.screenY(grabbedV), cursorY - 42.25, "drag moves ground with mouse vertically");
        double anchorU = view.projectedU(cursorX), anchorV = view.projectedV(cursorY);
        checkZoom(view, cursorX, cursorY, anchorU, anchorV, 1, 2.5);
        checkZoom(view, cursorX, cursorY, anchorU, anchorV, -1, 2);
        checkZoom(view, cursorX, cursorY, anchorU, anchorV, 10000, 4);
        double atMaxU = view.centerU(), atMaxV = view.centerV();
        checkZoom(view, cursorX, cursorY, anchorU, anchorV, 1, 4);
        require(view.centerU() == atMaxU && view.centerV() == atMaxV, "saturated zoom does not drift");
        checkZoom(view, cursorX, cursorY, anchorU, anchorV, -10000, 0.5);
        checkZoom(view, cursorX, cursorY, anchorU, anchorV, 0, 0.5);

        view.recenter(1024.25, -257.75);
        require(
            view.centerU() == 1024.25 && view.centerV() == -257.75 && view.pixelsPerUnit() == 0.5,
            "recenter retains scale");
        view.resize(4, 8, 120, 60);
        close(view.screenX(1024.25), 64, "resized screen center X");
        close(view.screenY(-257.75), 38, "resized screen center Y");
        require(
            view.centerU() == 1024.25 && view.centerV() == -257.75 && view.pixelsPerUnit() == 0.5,
            "resize preserves camera");
    }

    private static void checkZoom(MapViewportController view, double x, double y, double u, double v, double step,
        double expectedScale) {
        view.zoomAt(x, y, step);
        close(view.pixelsPerUnit(), expectedScale, "wheel scale");
        close(view.projectedU(x), u, "zoom keeps cursor U fixed");
        close(view.projectedV(y), v, "zoom keeps cursor V fixed");
    }

    private static void checkClipping() {
        MapViewportController view = new MapViewportController(0, 0, 1, 0.25, 4);
        require(
            view.tiles()
                .isEmpty() && view.clip(key(0, 0)) == null,
            "uninitialized view has no area");
        view.resize(7, 13, UNITS, UNITS / 2);
        List<Tile> tiles = view.tiles();
        require(
            tiles.stream()
                .map(Tile::key)
                .toList()
                .equals(List.of(key(-1, -1), key(0, -1), key(-1, 0), key(0, 0))),
            "negative tiles share origin in row-major order");
        checkTile(tiles.get(0), 7, 13, 7 + UNITS / 2, 13 + UNITS / 4, 0.5, 0.75, 1, 1);
        checkTile(tiles.get(3), 7 + UNITS / 2, 13 + UNITS / 4, 7 + UNITS, 13 + UNITS / 2, 0, 0, 0.5, 0.25);
        require(
            tiles.get(0)
                .x1()
                == tiles.get(1)
                    .x0()
                && tiles.get(0)
                    .y1()
                    == tiles.get(2)
                        .y0(),
            "shared seams exact");
        for (Tile tile : tiles) require(tile.equals(view.clip(tile.key())), "sparse clipping equals enumeration");
        require(view.clip(key(-2, 0)) == null, "off-screen tile rejected");
        TileKey overview = key(-1, -1).parent();
        Tile coarse = view.clip(overview);
        require(
            coarse != null && coarse.key()
                .equals(overview),
            "overview key retained");
        checkTile(coarse, 7, 13, 7 + UNITS / 2, 13 + UNITS / 4, 0.75, 0.875, 1, 1);

        view.resize(7, 13, UNITS, UNITS);
        view.recenter(UNITS / 2.0, -UNITS / 2.0);
        require(
            view.tiles()
                .stream()
                .map(Tile::key)
                .toList()
                .equals(List.of(key(0, -1))),
            "edge-only tiles excluded");
        checkTile(view.clip(key(0, -1)), 7, 13, 7 + UNITS, 13 + UNITS, 0, 0, 1, 1);
        view.panBy(0.125, 0);
        Tile thin = view.clip(key(-1, -1));
        require(thin != null && thin.x1() - thin.x0() == 0.125, "subpixel intersections kept");
        view.recenter(
            Integer.MIN_VALUE * (double) UNITS + UNITS / 2.0,
            Integer.MAX_VALUE * (double) UNITS + UNITS / 2.0);
        require(
            view.tiles()
                .stream()
                .map(Tile::key)
                .toList()
                .equals(List.of(key(Integer.MIN_VALUE, Integer.MAX_VALUE))),
            "extreme signed tile indices do not wrap");
        view.resize(7, 13, UNITS, 0);
        require(
            view.tiles()
                .isEmpty() && view.clip(key(0, -1)) == null,
            "empty resized view has no area");
    }

    private static void checkOverview() {
        double cutoff = 0.5;
        MapViewportController view = new MapViewportController(0, 0, cutoff, 0.25, 4);
        require(view.preferredLod() == 0, "canonical cutoff stays detailed");
        require(
            new MapViewportController(0, 0, Math.nextUp(cutoff), 0.25, 4).preferredLod() == 0,
            "just above canonical cutoff stays detailed");
        require(
            new MapViewportController(0, 0, Math.nextDown(cutoff), 0.25, 4).preferredLod() == 1,
            "just below canonical cutoff prefers overview");
        require(
            view.tiles(1)
                .isEmpty(),
            "uninitialized view has no overview tiles");
        checkRejectedLod(view, -1);
        checkRejectedLod(view, 2);

        view.resize(7, 13, UNITS, UNITS / 2);
        List<Tile> overview = view.tiles(1);
        require(
            overview.stream()
                .map(Tile::key)
                .toList()
                .equals(List.of(overviewKey(-1, -1), overviewKey(0, -1), overviewKey(-1, 0), overviewKey(0, 0))),
            "explicit overview tiles cross the origin in row-major order even at detail zoom");
        checkTile(overview.get(0), 7, 13, 7 + UNITS / 2, 13 + UNITS / 4, 0.5, 0.75, 1, 1);
        checkTile(overview.get(3), 7 + UNITS / 2, 13 + UNITS / 4, 7 + UNITS, 13 + UNITS / 2, 0, 0, 0.5, 0.25);
        for (Tile tile : overview) require(tile.equals(view.clip(tile.key())), "overview clipping matches enumeration");

        view.resize(7, 13, UNITS, UNITS);
        view.recenter(-UNITS, UNITS);
        List<Tile> aligned = view.tiles(1);
        require(aligned.size() == 1, "overview excludes edge-only intersections");
        require(
            aligned.get(0)
                .key()
                .equals(overviewKey(-1, 0)),
            "aligned negative overview key");
        checkTile(aligned.get(0), 7, 13, 7 + UNITS, 13 + UNITS, 0, 0, 1, 1);

        double centerX = view.screenX(view.centerU()), centerY = view.screenY(view.centerV());
        view.zoomAt(centerX, centerY, -1);
        require(view.preferredLod() == 1, "zooming out crosses into overview mode");
        require(
            view.tiles()
                .equals(view.tiles(0)),
            "default enumeration remains explicit detail below cutoff");
        view.zoomAt(centerX, centerY, 1);
        require(view.preferredLod() == 0, "zooming back to cutoff restores detail mode");
    }

    private static void checkRejectedLod(MapViewportController view, int lod) {
        try {
            view.tiles(lod);
            throw new AssertionError("Expected invalid LOD to be rejected: " + lod);
        } catch (IllegalArgumentException expected) {
            require(true, "reject invalid enumeration LOD " + lod);
        }
    }

    private static void checkFixedMinimapParity() {
        double[] centers = { -29_999_983.625, -141.5, -128, -116, -0.25, 0, 12, 64, 116, 127.875, 128, 29_999_984 };
        int[] sizes = { 96, 63, 1 };
        for (int size : sizes) {
            double scale = size / MinimapViewport.SPAN;
            MapViewportController view = new MapViewportController(0, 0, scale, scale, scale);
            view.resize(20, 6, size, size);
            for (double u : centers) {
                for (double v : centers) {
                    view.recenter(u, v);
                    List<MinimapViewport.Tile> expected = new MinimapViewport(u, v).tiles();
                    List<Tile> actual = view.tiles();
                    require(actual.size() == expected.size(), "HUD tile count");
                    for (int i = 0; i < expected.size(); i++) {
                        MinimapViewport.Tile crop = expected.get(i);
                        Tile tile = actual.get(i);
                        require(
                            tile.key()
                                .equals(crop.key()),
                            "HUD tile key");
                        checkTile(
                            tile,
                            20 + crop.x0() * size,
                            6 + crop.y0() * size,
                            20 + crop.x1() * size,
                            6 + crop.y1() * size,
                            crop.u0(),
                            crop.v0(),
                            crop.u1(),
                            crop.v1());
                    }
                }
            }
        }
    }

    private static void checkTile(Tile tile, double x0, double y0, double x1, double y1, double u0, double v0,
        double u1, double v1) {
        require(tile != null, "tile visible");
        close(tile.x0(), x0, "left GUI edge");
        close(tile.y0(), y0, "top GUI edge");
        close(tile.x1(), x1, "right GUI edge");
        close(tile.y1(), y1, "bottom GUI edge");
        close(tile.u0(), u0, "left texture edge");
        close(tile.v0(), v0, "top texture edge");
        close(tile.u1(), u1, "right texture edge");
        close(tile.v1(), v1, "bottom texture edge");
    }

    private static TileKey key(int u, int v) {
        return new TileKey(TileKey.SURFACE, u, v);
    }

    private static TileKey overviewKey(int u, int v) {
        return new TileKey(TileKey.SURFACE, u, v, 1);
    }

    private static void close(double actual, double expected, String description) {
        require(Math.abs(actual - expected) <= 1e-10, description + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
        digest.update(description.getBytes(StandardCharsets.UTF_8));
    }
}
