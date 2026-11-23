package dev.diorama.gtnh.client;

import java.util.ArrayList;
import java.util.List;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;

/** The fixed, north-facing surface crop of the canonical projected tile grid. */
final class MinimapViewport {

    static final double SPAN = 24;
    static final double HALF = SPAN / 2;
    static final int MARGIN = 6;
    private static final double TILE_UNITS = Projection.TILE_UNITS;
    private static final double NORTH_SIN = Math.sin(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()));
    private final List<Tile> tiles;

    /** The player position already projected with the canonical default pitch and north facing. */
    MinimapViewport(double u, double v) {
        List<Axis> columns = axis(u), rows = axis(v);
        List<Tile> visible = new ArrayList<>(columns.size() * rows.size());
        for (Axis row : rows) {
            for (Axis column : columns) {
                visible.add(
                    new Tile(
                        new TileKey(TileKey.SURFACE, column.index(), row.index()),
                        column.start(),
                        row.start(),
                        column.end(),
                        row.end(),
                        column.uvStart(),
                        row.uvStart(),
                        column.uvEnd(),
                        row.uvEnd()));
            }
        }
        tiles = List.copyOf(visible);
    }

    /** Positive-area tile intersections in row-major order. Edges and UVs are normalized to [0, 1]. */
    List<Tile> tiles() {
        return tiles;
    }

    private static List<Axis> axis(double center) {
        if (!Double.isFinite(center) || center < Integer.MIN_VALUE * TILE_UNITS + HALF
            || center > (Integer.MAX_VALUE + 1.0) * TILE_UNITS - HALF) {
            throw new IllegalArgumentException("Minimap viewport outside projected tile grid");
        }
        int index = (int) Math.floor(center / TILE_UNITS);
        double tileStart = index * TILE_UNITS;
        double tileEnd = tileStart + TILE_UNITS;
        // Compare the center to the exact touch points: a touching edge has zero area. Using
        // center +/- HALF to test overlap could round a thin positive intersection down to zero.
        if (center < tileStart + HALF) {
            double overlap = (tileStart + HALF) - center;
            double seam = overlap / SPAN;
            return List.of(
                new Axis(index - 1, 0, seam, 1 - overlap / TILE_UNITS, 1),
                new Axis(index, seam, 1, 0, (SPAN - overlap) / TILE_UNITS));
        }
        if (center > tileEnd - HALF) {
            double overlap = center - (tileEnd - HALF);
            double seam = 1 - overlap / SPAN;
            return List.of(
                new Axis(index, 0, seam, 1 - (SPAN - overlap) / TILE_UNITS, 1),
                new Axis(index + 1, seam, 1, 0, overlap / TILE_UNITS));
        }
        return List.of(
            new Axis(
                index,
                0,
                1,
                (center - (tileStart + HALF)) / TILE_UNITS,
                (center - (tileStart - HALF)) / TILE_UNITS));
    }

    /** Integer GUI bounds; only the inner tile seams may fall between GUI pixels. */
    static Bounds fit(int width, int height, int preferredSize) {
        if (width <= 2 * MARGIN || height <= 2 * MARGIN) return new Bounds(0, 0, 0);
        int size = Math.min(preferredSize, Math.min(width - 2 * MARGIN, height - 2 * MARGIN));
        return new Bounds(width - MARGIN - size, MARGIN, size);
    }

    /** Modern Markers.onMap and flat-arrow direction, fixed to the canonical north projection. */
    static Heading heading(float yaw) {
        double radians = Math.toRadians(yaw - 90 * Facing.NORTH.turns);
        double dx = -Math.sin(radians);
        double dy = Math.cos(radians) * NORTH_SIN;
        double length = Math.sqrt(dx * dx + dy * dy);
        return new Heading(dx / length, dy / length);
    }

    record Tile(TileKey key, double x0, double y0, double x1, double y1, double u0, double v0, double u1, double v1) {}

    record Bounds(int x, int y, int size) {}

    record Heading(double dx, double dy) {}

    private record Axis(int index, double start, double end, double uvStart, double uvEnd) {}
}
