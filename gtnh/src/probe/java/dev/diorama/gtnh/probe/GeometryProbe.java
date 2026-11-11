package dev.diorama.gtnh.probe;

import java.security.MessageDigest;
import java.util.List;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;

/** Offline shared geometry/value contracts, not a terrain bake or GL test. */
public final class GeometryProbe {

    private static final MessageDigest DIGEST = digest();
    private static int checks;

    public static void main(String[] arguments) {
        rotations();
        projections();
        styles();
        tiles();
        images();
        StringBuilder hex = new StringBuilder();
        for (byte value : DIGEST.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println("PASS shared geometry/value contracts; checks=" + checks + "; TileBaker/GL UNTESTED");
    }

    private static void rotations() {
        int[] coordinates = { -4097, -129, -128, -17, -16, -1, 0, 1, 15, 16, 127, 128, 4096 };
        for (Facing facing : Facing.values()) {
            require(Facing.of(facing.id()) == facing, "facing id");
            require(
                facing.next(4) == facing && facing.next(-1)
                    .next(1) == facing,
                "facing cycle");
            for (int x : coordinates) for (int z : coordinates) {
                // Independent quarter-turn oracle: rotate block centers, then take their containing block.
                double px = x + 0.5;
                double pz = z + 0.5;
                for (int turn = 0; turn < facing.turns; turn++) {
                    double oldX = px;
                    px = pz;
                    pz = -oldX;
                }
                int tx = facing.blockX(x, z);
                int tz = facing.blockZ(x, z);
                require(tx == (int) Math.floor(px) && tz == (int) Math.floor(pz), "block center oracle");
                require(facing.worldBlockX(tx, tz) == x && facing.worldBlockZ(tx, tz) == z, "block inverse");
                near(facing.x(x + 0.5, z + 0.5), px, "point x");
                near(facing.z(x + 0.5, z + 0.5), pz, "point z");
                near(facing.worldX(px, pz), x + 0.5, "point inverse x");
                near(facing.worldZ(px, pz), z + 0.5, "point inverse z");
                require(Math.floorDiv(tx, 16) == facing.blockX(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), "chunk x");
                require(Math.floorDiv(tz, 16) == facing.blockZ(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), "chunk z");
                put(tx);
                put(tz);
            }
        }
        require(Facing.of("invalid") == Facing.NORTH, "unknown facing fallback");
    }

    private static void projections() {
        require(
            Projection.TILE_UNITS == 128 && Projection.TILE_PIXELS == 512 && Projection.COVERAGE_CELLS == 64,
            "tile geometry");
        try {
            for (double pitch : new double[] { 25, 50, 60, 90 }) {
                MapStyle.set(MapStyle.DEFAULT.withPitch(pitch));
                near(Projection.sin(), Math.sin(Math.toRadians(pitch)), "pitch sin");
                near(Projection.cos(), Math.cos(Math.toRadians(pitch)), "pitch cos");
                for (Facing facing : Facing.values()) {
                    Projection.setFacing(facing);
                    for (double y : new double[] { -64, 0, 64, 255 }) {
                        double x = -128.25;
                        double z = 127.75;
                        double u = Projection.u(x, z);
                        double v = Projection.v(x, y, z);
                        near(u, facing.x(x, z), "projection column");
                        near(Projection.zAt(v, y), facing.z(x, z), "ray height intersection");
                        near(facing.worldX(u, Projection.zAt(v, y)), x, "projection inverse x");
                        near(facing.worldZ(u, Projection.zAt(v, y)), z, "projection inverse z");
                    }
                }
            }
        } finally {
            MapStyle.set(MapStyle.DEFAULT);
            Projection.setFacing(Facing.NORTH);
        }
    }

    private static void styles() {
        MapStyle base = MapStyle.DEFAULT;
        require(base.looksLike(base.withName("renamed")), "name is not baked");
        require(base.looksLike(base.withRoundedFog(!base.roundedFog())), "fog edge is not baked");
        require(!base.looksLike(base.withPitch(60)), "pitch changes bake");
        require(!base.looksLike(base.withClearWater(!base.clearWater())), "water changes bake");
        require(!base.looksLike(base.withOutline(base.outline() + 0.1F)), "outline changes bake");
        near(
            base.withSunElevation(-100)
                .sunElevation(),
            MapStyle.MIN_SUN_ELEVATION,
            "low sun clamp");
        near(
            base.withSunElevation(100)
                .sunElevation(),
            MapStyle.MAX_SUN_ELEVATION,
            "high sun clamp");
        List<MapStyle> tour = MapStyle.tour();
        require(tour.size() == MapStyle.VARIANTS.size() + 1 && tour.get(tour.size() - 1) == base, "style tour");
        try {
            tour.add(base);
            throw new AssertionError("mutable style tour");
        } catch (UnsupportedOperationException expected) {
            // Stream.toList must remain unmodifiable after downgrade.
        }
    }

    private static void tiles() {
        for (int lod = 0; lod <= TileKey.MAX_LOD; lod++) {
            for (int u = -5; u <= 5; u++) for (int v = -5; v <= 5; v++) {
                TileKey tile = new TileKey(TileKey.SURFACE, u, v, lod);
                require(!tile.isCave(), "surface marker");
                near(tile.units(), 128 * (1 << lod), "lod scale");
                near(tile.minU(), u * tile.units(), "tile min u");
                near(tile.minV(), v * tile.units(), "tile min v");
                TileKey parent = tile.parent();
                require(parent.lod() == lod + 1 && parent.layer() == tile.layer(), "parent fields");
                require(
                    parent.minU() <= tile.minU() && tile.minU() + tile.units() <= parent.minU() + parent.units(),
                    "parent contains u");
                require(
                    parent.minV() <= tile.minV() && tile.minV() + tile.units() <= parent.minV() + parent.units(),
                    "parent contains v");
                require(tile.equals(new TileKey(tile.layer(), u, v, lod)), "record equality");
                require(tile.hashCode() == new TileKey(tile.layer(), u, v, lod).hashCode(), "record hash");
                put(parent.tileU());
                put(parent.tileV());
                put(parent.lod());
            }
        }
        require(new TileKey(32, -1, 0).isCave(), "cave marker");
        require(
            new TileKey(TileKey.SURFACE, -1, -3).parent()
                .equals(new TileKey(TileKey.SURFACE, -1, -2, 1)),
            "negative floor parent");
    }

    private static void images() {
        TileKey key = new TileKey(TileKey.SURFACE, -1, 1);
        int[][] mips = { new int[] { 0xFF123456 } };
        byte[] coverage = { (byte) 255 };
        byte[] info = { 3 };
        int[] spots = { 7 };
        byte[] light = { 4 };
        TileImage full = TileImage.full(key, mips, coverage, info);
        require(
            full.isFull() && full.patches()
                .isEmpty() && full.generation() == 0,
            "full image");
        TileImage tagged = full.withGeneration(17)
            .withSpots(spots)
            .withBlockLight(light);
        require(
            tagged.key() == key && tagged.mips() == mips && tagged.coverage() == coverage && tagged.info() == info,
            "tag retains payload");
        require(tagged.generation() == 17 && tagged.spots() == spots && tagged.blockLight() == light, "image tags");
        require(
            full.generation() == 0 && full.spots() == null && full.blockLight() == null,
            "tag does not mutate record");
        TileImage.Patch patch = new TileImage.Patch(2, 3, mips, coverage, info, light);
        TileImage partial = TileImage.partial(key, List.of(patch));
        require(
            !partial.isFull() && partial.mips() == null && partial.coverage() == null && partial.info() == null,
            "partial image");
        require(
            partial.patches()
                .get(0) == patch && patch.blockX() == 2
                && patch.blockY() == 3,
            "patch location");
        require(
            patch.mips() == mips && patch.coverage() == coverage && patch.info() == info && patch.blockLight() == light,
            "patch payload");
        require(TileImage.BLOCK == 32 && Projection.TILE_PIXELS % TileImage.BLOCK == 0, "patch alignment");
        put(mips[0][0]);
        put(tagged.generation());
    }

    private static void near(double actual, double expected, String label) {
        require(Math.abs(actual - expected) < 1.0E-9, label);
    }

    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void put(int value) {
        DIGEST.update((byte) (value >>> 24));
        DIGEST.update((byte) (value >>> 16));
        DIGEST.update((byte) (value >>> 8));
        DIGEST.update((byte) value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
