package dev.diorama.gtnh.client;

import java.util.ArrayList;
import java.util.List;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;

/** Shared map camera and tile geometry, in projected map units and GUI pixels. */
final class MapViewportController {

    private static final double WHEEL_FACTOR = 1.25;
    // Match the detailed zoom cutoff in the modern MapViewport, in GUI pixels per unit.
    private static final double DETAILED_ZOOM = 0.5;
    private final double minPixelsPerUnit, maxPixelsPerUnit;
    private double centerU, centerV, pixelsPerUnit;
    private int x, y, width, height;

    MapViewportController(double centerU, double centerV, double pixelsPerUnit, double minPixelsPerUnit,
        double maxPixelsPerUnit) {
        if (!(minPixelsPerUnit > 0) || !(maxPixelsPerUnit >= minPixelsPerUnit)
            || !Double.isFinite(maxPixelsPerUnit)
            || !Double.isFinite(pixelsPerUnit)
            || !(pixelsPerUnit > 0)) {
            throw new IllegalArgumentException("Invalid map scale");
        }
        this.minPixelsPerUnit = minPixelsPerUnit;
        this.maxPixelsPerUnit = maxPixelsPerUnit;
        this.pixelsPerUnit = clampScale(pixelsPerUnit);
        recenter(centerU, centerV);
    }

    /** Change the GUI rectangle without moving or zooming the camera. Zero-sized views draw nothing. */
    void resize(int x, int y, int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Negative viewport size");
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    double centerU() {
        return centerU;
    }

    double centerV() {
        return centerV;
    }

    double pixelsPerUnit() {
        return pixelsPerUnit;
    }

    double screenX(double u) {
        return x + width / 2.0 + (u - centerU) * pixelsPerUnit;
    }

    double screenY(double v) {
        return y + height / 2.0 + (v - centerV) * pixelsPerUnit;
    }

    double projectedU(double mouseX) {
        return centerU + (mouseX - (x + width / 2.0)) / pixelsPerUnit;
    }

    double projectedV(double mouseY) {
        return centerV + (mouseY - (y + height / 2.0)) / pixelsPerUnit;
    }

    /** Recenter on an already projected point, retaining the current zoom. */
    void recenter(double u, double v) {
        if (!Double.isFinite(u) || !Double.isFinite(v)) throw new IllegalArgumentException("Invalid map center");
        centerU = u;
        centerV = v;
    }

    /** Drag the map content by this many GUI pixels. */
    void panBy(double dx, double dy) {
        recenter(centerU - dx / pixelsPerUnit, centerV - dy / pixelsPerUnit);
    }

    /** Positive wheel steps zoom in. Keep the cursor's map point fixed, including on a clamped step. */
    void zoomAt(double mouseX, double mouseY, double steps) {
        if (!Double.isFinite(steps)) throw new IllegalArgumentException("Invalid map wheel input");
        double next = clampScale(pixelsPerUnit * Math.pow(WHEEL_FACTOR, steps));
        if (next == pixelsPerUnit) return;
        double offsetX = mouseX - (x + width / 2.0);
        double offsetY = mouseY - (y + height / 2.0);
        recenter(projectedU(mouseX) - offsetX / next, projectedV(mouseY) - offsetY / next);
        pixelsPerUnit = next;
    }

    private double clampScale(double scale) {
        return Math.max(minPixelsPerUnit, Math.min(maxPixelsPerUnit, scale));
    }

    /** Preferred shared surface LOD by zoom only; callers may fall back to detail if needed. */
    int preferredLod() {
        return pixelsPerUnit < DETAILED_ZOOM ? 1 : 0;
    }

    /** Visible detail surface tiles in row-major order. */
    List<Tile> tiles() {
        return tiles(0);
    }

    /** Visible surface tiles at the requested LOD, in row-major order. Use {@link #clip} for sparse maps. */
    List<Tile> tiles(int lod) {
        if (lod < 0 || lod > 1) throw new IllegalArgumentException("Expected surface LOD0 or LOD1");
        if (width == 0 || height == 0) return List.of();
        double units = (double) Projection.TILE_UNITS * (1 << lod);
        // Include boundary candidates, then use the same positive-area test as sparse clipping.
        // Do not narrow to int before looping: the exclusive end may be Integer.MAX_VALUE + 1.
        long firstU = firstTile(projectedU(x), units);
        long firstV = firstTile(projectedV(y), units);
        long endU = endTile(projectedU(x + (double) width), units);
        long endV = endTile(projectedV(y + (double) height), units);
        if (firstU >= endU || firstV >= endV) return List.of();
        List<Tile> visible = new ArrayList<>();
        for (long v = firstV; v < endV; v++) {
            for (long u = firstU; u < endU; u++) {
                Tile tile = clip(new TileKey(TileKey.SURFACE, (int) u, (int) v, lod));
                if (tile != null) visible.add(tile);
            }
        }
        return List.copyOf(visible);
    }

    private static long firstTile(double at, double units) {
        return (long) Math.max(Integer.MIN_VALUE, Math.floor(at / units) - 1);
    }

    private static long endTile(double at, double units) {
        return (long) Math.min(Integer.MAX_VALUE + 1.0, Math.floor(at / units) + 1);
    }

    /** Clip a known tile at any supported LOD. Returns null for no positive-area intersection. */
    Tile clip(TileKey key) {
        if (width == 0 || height == 0) return null;
        if (key.lod() < 0 || key.lod() > TileKey.MAX_LOD) throw new IllegalArgumentException("Invalid tile LOD");
        double units = key.units();
        double left = key.minU(), top = key.minV();
        double sx0 = screenX(left), sy0 = screenY(top);
        double sx1 = screenX(left + units), sy1 = screenY(top + units);
        double x0 = Math.max(x, sx0), y0 = Math.max(y, sy0);
        double x1 = Math.min(x + (double) width, sx1), y1 = Math.min(y + (double) height, sy1);
        if (!(x0 < x1 && y0 < y1)) return null;
        double size = units * pixelsPerUnit;
        return new Tile(
            key,
            x0,
            y0,
            x1,
            y1,
            x0 == sx0 ? 0 : uv((x0 - sx0) / size),
            y0 == sy0 ? 0 : uv((y0 - sy0) / size),
            x1 == sx1 ? 1 : uv(1 - (sx1 - x1) / size),
            y1 == sy1 ? 1 : uv(1 - (sy1 - y1) / size));
    }

    private static double uv(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** Clipped absolute GUI edges paired with normalized source texture edges. */
    record Tile(TileKey key, double x0, double y0, double x1, double y1, double u0, double v0, double u1, double v1) {}
}
