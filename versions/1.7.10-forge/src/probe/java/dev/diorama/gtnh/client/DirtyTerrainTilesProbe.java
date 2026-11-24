package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.BitSet;

import dev.diorama.bake.TileKey;

/** Offline regression fixtures for the default north-facing surface projection. */
public final class DirtyTerrainTilesProbe {

    private static int assertions;
    private static MessageDigest digest;

    private DirtyTerrainTilesProbe() {}

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        TileKey origin = tile(0, 0);
        TileKey west = tile(-1, 0);
        TileKey north = tile(0, -1);
        rect(DirtyTerrainTiles.block(origin, 64, 64, 128), 5, 12, 3, 11, "interior projection");
        rect(DirtyTerrainTiles.block(west, -26, 64, 128), 10, 15, 3, 11, "negative U seam, west side");
        rect(DirtyTerrainTiles.block(origin, -26, 64, 128), 0, 1, 3, 11, "eastward shadow from outside tile");
        rect(DirtyTerrainTiles.block(north, 64, 64, 80), 5, 12, 15, 15, "negative V seam, north side");
        rect(DirtyTerrainTiles.block(origin, 64, 64, 80), 5, 12, 0, 7, "negative V seam, south side");
        rect(DirtyTerrainTiles.block(tile(-1, -1), -128, 64, -112), 0, 4, 0, 4, "negative world coordinates");
        rect(DirtyTerrainTiles.block(tile(-2, -1), -128, 64, -112), 13, 15, 0, 4, "nonzero negative U seam");
        rect(DirtyTerrainTiles.block(origin, 142, 64, 128), 15, 15, 3, 11, "light reaches beyond sky-occlusion halo");
        rect(DirtyTerrainTiles.block(origin, 144, 64, 128), 15, 15, 3, 11, "one-pixel outline crosses U seam");
        check(
            DirtyTerrainTiles.block(origin, 145, 64, 128)
                .isEmpty(),
            "one block past the U halo");
        check(
            DirtyTerrainTiles.block(origin, 64, 64, -96)
                .isEmpty(),
            "outside tile on V only");
        check(
            DirtyTerrainTiles.block(tile(2, 0), 64, 64, 128)
                .isEmpty(),
            "outside tile on U only");

        BitSet section = DirtyTerrainTiles.section(origin, 3, 4, 7);
        rect(section, 3, 12, 1, 11, "one whole section, including its last blocks");
        check(section.equals(DirtyTerrainTiles.range(origin, 48, 64, 112, 63, 79, 127)), "inclusive range");
        rect(DirtyTerrainTiles.chunk(origin, 3, 7), 3, 12, 0, 15, "chunk availability spans the tile height");
        rect(DirtyTerrainTiles.chunk(north, 3, 7), 3, 12, 2, 15, "same chunk across V seam");
        check(
            DirtyTerrainTiles.section(north, 3, 4, 7)
                .isEmpty(),
            "known section needn't dirty whole chunk height");
        check(
            DirtyTerrainTiles.chunk(tile(-1, 0), 3, 7)
                .isEmpty(),
            "unrelated tile stays clean for chunk change");
        check(
            DirtyTerrainTiles.section(west, -1, 4, 7)
                .equals(DirtyTerrainTiles.range(west, -16, 64, 112, -1, 79, 127)),
            "negative chunk conversion");
        check(
            DirtyTerrainTiles.chunk(origin, Integer.MAX_VALUE, 7)
                .isEmpty(),
            "chunk X must not wrap");
        check(
            DirtyTerrainTiles.section(origin, 3, Integer.MIN_VALUE, 7)
                .isEmpty(),
            "section Y must not wrap");
        check(
            DirtyTerrainTiles.block(origin, 64, 256, 128)
                .isEmpty(),
            "above legacy build height");
        check(
            DirtyTerrainTiles.range(origin, 64, -9, 128, 64, 0, 128)
                .equals(DirtyTerrainTiles.block(origin, 64, 0, 128)),
            "range clips to legacy build height");
        check(
            DirtyTerrainTiles.block(new TileKey(64, 0, 0), 64, 64, 128)
                .isEmpty(),
            "no cave tiles");
        check(
            DirtyTerrainTiles.block(origin.parent(), 64, 64, 128)
                .isEmpty(),
            "no overview tiles");
        rect(
            DirtyTerrainTiles
                .range(origin, Integer.MIN_VALUE, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, 255, Integer.MAX_VALUE),
            0,
            15,
            0,
            15,
            "huge box still fits 256 patch bits");

        section.clear();
        rect(DirtyTerrainTiles.section(origin, 3, 4, 7), 3, 12, 1, 11, "caller owns every mask");
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println("DirtyTerrainTilesProbe: " + assertions + " checks passed");
    }

    private static TileKey tile(int u, int v) {
        return new TileKey(TileKey.SURFACE, u, v);
    }

    private static void rect(BitSet actual, int col0, int col1, int row0, int row1, String label) {
        BitSet expected = new BitSet(256);
        for (int row = row0; row <= row1; row++) {
            for (int col = col0; col <= col1; col++) expected.set(row * 16 + col);
        }
        check(actual.equals(expected), label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
        digest.update(label.getBytes(StandardCharsets.UTF_8));
    }
}
