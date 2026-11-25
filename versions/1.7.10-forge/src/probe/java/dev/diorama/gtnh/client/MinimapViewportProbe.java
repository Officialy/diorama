package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.client.MinimapViewport.Bounds;
import dev.diorama.gtnh.client.MinimapViewport.Heading;
import dev.diorama.gtnh.client.MinimapViewport.Tile;

/** Canonical tile selection, clipping, GUI fit and projected marker checks without Minecraft. */
public final class MinimapViewportProbe {

    private static final double EPSILON = 1e-10;
    private static final int UNITS = Projection.TILE_UNITS;
    private static int assertions;
    private static MessageDigest hash;

    public static void main(String[] args) throws Exception {
        hash = MessageDigest.getInstance("SHA-256");
        require(MinimapViewport.SPAN == 24 && MinimapViewport.HALF == 12, "fixed 24-unit span");
        List<Tile> origin = new MinimapViewport(0, 0).tiles();
        require(origin.size() == 4, "origin spans four tiles");
        require(
            origin.get(0)
                .key()
                .equals(key(-1, -1))
                && origin.get(1)
                    .key()
                    .equals(key(0, -1))
                && origin.get(2)
                    .key()
                    .equals(key(-1, 0))
                && origin.get(3)
                    .key()
                    .equals(key(0, 0)),
            "row-major origin keys");
        require(
            origin.get(0)
                .x1()
                == origin.get(1)
                    .x0()
                && origin.get(0)
                    .y1()
                    == origin.get(2)
                        .y0(),
            "neighbours share exact seams");
        checkOne(12, -12, 0, -1);
        checkOne(116, 140, 0, 1);
        checkOne(-116, -140, -1, -2);
        checkOne(-140, -116, -2, -1);
        checkOne(
            Integer.MIN_VALUE * (double) UNITS + 12,
            (Integer.MAX_VALUE + 1.0) * UNITS - 12,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE);
        require(
            new MinimapViewport(Math.nextUp(116.0), 64).tiles()
                .size() == 2,
            "right edge crosses by one ulp");
        require(
            new MinimapViewport(Math.nextDown(12.0), 64).tiles()
                .size() == 2,
            "left edge crosses by one ulp");
        require(
            new MinimapViewport(64, Math.nextUp(-12.0)).tiles()
                .size() == 2,
            "bottom edge crosses zero by one ulp");
        require(
            new MinimapViewport(64, Math.nextDown(-116.0)).tiles()
                .size() == 2,
            "top edge crosses negative seam");

        double[] samples = { -29_999_983.75, -140.25, -140, -128.5, -128, -116, -64, -12, -0.25, 0, 11.75, 12, 64, 116,
            116.25, 128, 140, 29_999_983.75 };
        for (double u : samples) {
            for (double v : samples) checkCrop(u, v);
        }
        checkRejected(Double.NaN);
        checkRejected(Double.POSITIVE_INFINITY);
        checkRejected(Integer.MIN_VALUE * (double) UNITS + 11);
        checkRejected((Integer.MAX_VALUE + 1.0) * UNITS - 11);
        checkFit(320, 240, 218, 6, 96);
        checkFit(100, 240, 6, 6, 88);
        checkFit(320, 76, 250, 6, 64);
        checkFit(13, 13, 6, 6, 1);
        checkFit(12, 240, 0, 0, 0);
        checkFit(320, 0, 0, 0, 0);
        require(
            MinimapViewport.fit(320, 240, 64)
                .equals(new Bounds(250, 6, 64)),
            "smaller HUD");
        require(
            MinimapViewport.fit(320, 240, 128)
                .equals(new Bounds(186, 6, 128)),
            "larger HUD");
        require(
            MinimapViewport.fit(108, 70, 128)
                .equals(new Bounds(44, 6, 58)),
            "larger HUD clipped to window");
        checkOptions();
        require(MinimapViewport.caveLayer(61, TileKey.SURFACE) == 68, "four-block initial cut");
        require(MinimapViewport.caveLayer(65, 68) == 68, "small jump does not shift the cut");
        require(MinimapViewport.caveLayer(66, 68) == 72, "ascend when cut nears the player");
        require(MinimapViewport.caveLayer(60, 72) == 64, "descend immediately");
        require(MinimapViewport.caveLayer(255, TileKey.SURFACE) == 260, "top-of-world layer identity");
        require(MinimapViewport.stepCaveLayer(64, true) == 68, "manual cut ascends four blocks");
        require(MinimapViewport.stepCaveLayer(64, false) == 60, "manual cut descends four blocks");
        require(MinimapViewport.stepCaveLayer(4, false) == 4, "manual cut floor");
        require(MinimapViewport.stepCaveLayer(260, true) == 256, "manual cut top clamps to storage");
        require(
            new MinimapViewport(0, 0, 68).tiles()
                .stream()
                .allMatch(
                    tile -> tile.key()
                        .layer() == 68),
            "HUD only enumerates requested layer");
        double u = Projection.u(-15.5, 29.5), v = Projection.v(-15.5, 64, 29.5);
        var markerView = new MinimapViewport(u, v);
        close(markerView.normalizedX(u), 0.5, "waypoint center X");
        close(markerView.normalizedY(v), 0.5, "waypoint center Y");
        close(markerView.normalizedX(u - 12), 0, "waypoint at west crop edge");
        close(markerView.normalizedY(v + 12), 1, "waypoint at bottom crop edge");
        checkHeading(0, 0, 1);
        checkHeading(90, -1, 0);
        checkHeading(180, 0, -1);
        checkHeading(-90, 1, 0);
        checkHeading(450, -1, 0);
        Heading diagonal = MinimapViewport.heading(45);
        require(diagonal.dx() < 0 && diagonal.dy() > 0, "positive yaw turns from south toward west");
        close(diagonal.dy() / -diagonal.dx(), Projection.sin(), "diagonal is foreshortened by canonical pitch");
        close(Math.hypot(diagonal.dx(), diagonal.dy()), 1, "diagonal heading normalized");

        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out
            .println("PASS minimap viewport: " + assertions + " assertions; tile seams, crops, GUI fit and north yaw");
    }

    private static void checkOptions() throws Exception {
        Path dir = Files.createTempDirectory("diorama-minimap-options");
        Path file = dir.resolve("config")
            .resolve("diorama-minimap.properties");
        try {
            MinimapOptions first = new MinimapOptions(file);
            first.load();
            require(first.visible && first.coordinates && first.size() == 96, "missing file uses defaults");
            require(!Files.exists(file), "loading defaults does not write");
            first.visible = false;
            first.coordinates = false;
            first.cycleSize();
            first.save();
            MinimapOptions second = new MinimapOptions(file);
            second.load();
            require(!second.visible && !second.coordinates && second.size() == 128, "preferences survive reopen");
            second.visible = true;
            second.cycleSize();
            second.save();
            first.load();
            require(first.visible && !first.coordinates && first.size() == 64, "replacement and size wrap");
            first.cycleSize();
            require(first.size() == 96, "size returns to default");
            Files.write(file, "visible=nope\nsize=99999999999999999999\n".getBytes(StandardCharsets.UTF_8));
            first.load();
            require(first.visible && first.coordinates && first.size() == 96, "invalid or absent values use defaults");
            hash.update(
                (first.visible + ":" + first.coordinates + ":" + first.size()).getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(dir);
        }
    }

    private static void checkOne(double u, double v, int column, int row) {
        List<Tile> tiles = new MinimapViewport(u, v).tiles();
        require(
            tiles.size() == 1 && tiles.get(0)
                .key()
                .equals(key(column, row)),
            "zero-area neighbour excluded");
    }

    private static void checkCrop(double u, double v) {
        List<Tile> tiles = new MinimapViewport(u, v).tiles();
        double minU = u - 12, maxU = u + 12, minV = v - 12, maxV = v + 12;
        long firstU = (long) Math.floor(minU / UNITS), lastU = (long) Math.ceil(maxU / UNITS) - 1;
        long firstV = (long) Math.floor(minV / UNITS), lastV = (long) Math.ceil(maxV / UNITS) - 1;
        require(tiles.size() == (lastU - firstU + 1) * (lastV - firstV + 1), "exact number of intersecting tiles");
        Set<TileKey> seen = new HashSet<>();
        double area = 0;
        for (Tile tile : tiles) {
            TileKey key = tile.key();
            require(seen.add(key), "no duplicate tiles");
            require(key.layer() == TileKey.SURFACE && key.lod() == 0, "surface L0 only");
            require(
                key.tileU() >= firstU && key.tileU() <= lastU && key.tileV() >= firstV && key.tileV() <= lastV,
                "keys lie in the canonical range");
            double q0 = Math.max(minU, key.minU()), q1 = Math.min(maxU, key.minU() + UNITS);
            double r0 = Math.max(minV, key.minV()), r1 = Math.min(maxV, key.minV() + UNITS);
            range(tile.x0(), tile.x1(), "screen x");
            range(tile.y0(), tile.y1(), "screen y");
            range(tile.u0(), tile.u1(), "texture u");
            range(tile.v0(), tile.v1(), "texture v");
            close(tile.x0(), (q0 - minU) / 24, "screen left");
            close(tile.x1(), (q1 - minU) / 24, "screen right");
            close(tile.y0(), (r0 - minV) / 24, "screen top");
            close(tile.y1(), (r1 - minV) / 24, "screen bottom");
            close(tile.u0(), (q0 - key.minU()) / UNITS, "UV left");
            close(tile.u1(), (q1 - key.minU()) / UNITS, "UV right");
            close(tile.v0(), (r0 - key.minV()) / UNITS, "UV top");
            close(tile.v1(), (r1 - key.minV()) / UNITS, "UV bottom");
            for (Tile other : tiles) {
                if (tile == other) continue;
                require(
                    tile.x1() <= other.x0() || other.x1() <= tile.x0()
                        || tile.y1() <= other.y0()
                        || other.y1() <= tile.y0(),
                    "no screen-space overlaps");
            }
            area += (tile.x1() - tile.x0()) * (tile.y1() - tile.y0());
            hash.update((key.tileU() + "," + key.tileV() + ";").getBytes(StandardCharsets.UTF_8));
        }
        close(area, 1, "intersections fill the square with no gaps");
    }

    private static TileKey key(int column, int row) {
        return new TileKey(TileKey.SURFACE, column, row);
    }

    private static void range(double min, double max, String axis) {
        require(min >= 0 && min < max && max <= 1, axis + " is a positive crop within [0,1]");
    }

    private static void checkFit(int width, int height, int x, int y, int size) {
        require(
            MinimapViewport.fit(width, height, MinimapOptions.DEFAULT_SIZE)
                .equals(new Bounds(x, y, size)),
            "integer top-right HUD bounds");
    }

    private static void checkHeading(float yaw, double x, double y) {
        Heading heading = MinimapViewport.heading(yaw);
        close(heading.dx(), x, "cardinal yaw x");
        close(heading.dy(), y, "cardinal yaw y");
    }

    private static void checkRejected(double coordinate) {
        try {
            new MinimapViewport(coordinate, 0);
            throw new AssertionError("Invalid projected coordinate was accepted");
        } catch (IllegalArgumentException expected) {
            require(true, "invalid projected coordinate rejected");
        }
    }

    private static void close(double actual, double wanted, String label) {
        require(Math.abs(actual - wanted) < EPSILON, label + ": expected " + wanted + ", got " + actual);
    }

    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
