package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;

import dev.diorama.Facing;

/** Canonical decoded-section algorithms on numeric inputs, with no Minecraft classpath. */
public final class SectionProbe {

    private static final int AIR = 7;
    private static final int FULL = 513;
    private static final int SMALL = 2049;
    private static final int WATER = 31;
    private static final byte[] KINDS = new byte[4096];
    private static final int[] SHAPES = new int[4096];
    private static final MessageDigest HASH = digest();

    public static void main(String[] args) {
        KINDS[FULL] = Looks.SOLID;
        KINDS[SMALL] = Looks.SOLID;
        KINDS[WATER] = Looks.WATER;
        SHAPES[SMALL] = 1;
        SHAPES[WATER] = 3; // Non-solid shape metadata must not turn water into a partial solid.
        KINDS[0] = Looks.SOLID; // Air is supplied by the domain, not assumed to be id zero.
        sentinelsAndUniform();
        for (Facing facing : Facing.values()) {
            packed(facing, true);
            packed(facing, false);
            lazyByBlock(facing);
        }
        explicitIds();
        composedLooks();
        zeroMaskIdentity();
        columnPermutation();
        heightAndCaches();
        StringBuilder hex = new StringBuilder();
        for (byte value : HASH.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS decoded sections: uniform/packed/ids/lazy masks, four facings, nonzero air and heights; MC/UNKNOWN chunk cache/GL UNTESTED");
    }

    private static void sentinelsAndUniform() {
        DecodedSections.Air domain = new DecodedSections.Air(AIR);
        Fixture f = new Fixture(
            new DecodedSections.Section[] { null, single(AIR), single(FULL), single(SMALL), single(WATER) },
            -4,
            domain);
        int[] scratch = new int[4096];
        long[] empty = f.solid(0, KINDS, SHAPES, scratch);
        long[] uniformAir = f.solid(1, KINDS, SHAPES, scratch);
        require(empty == DecodedSections.NOTHING_SOLID && uniformAir != empty, "empty vs uniform identity");
        require(all(empty, 0) && all(uniformAir, 0), "empty mask values");
        require(f.solid(-1, KINDS, SHAPES, scratch) == empty, "out-of-range mask identity");
        require(f.partial(-1) == empty && f.partial(1) == empty, "empty partial identity");
        require(f.solid(2, KINDS, SHAPES, scratch) == DecodedSections.ALL_SOLID, "uniform full mask");
        require(f.partial(2) == empty, "full has no partial mask");
        require(
            f.solid(3, KINDS, SHAPES, scratch) == DecodedSections.ALL_SOLID
                && f.partial(3) == DecodedSections.ALL_SOLID,
            "uniform partial mask");
        require(f.solid(4, KINDS, SHAPES, scratch) == uniformAir, "uniform non-solid sentinel");
        for (int i = 0; i < 5; i++) {
            int expected = i < 2 ? AIR : i == 2 ? FULL : i == 3 ? SMALL : WATER;
            int[] ids = f.ids(i, scratch);
            for (int id : ids) require(id == expected, "uniform ids");
            require(f.ids(i, scratch) == ids && f.reads[i] == 1, "once-read section");
            put(expected);
            put((int) f.solid(i, KINDS, SHAPES, scratch)[0]);
        }
        int[] outside = f.ids(-1, scratch);
        require(outside == f.ids(5, scratch) && outside == f.ids(0, scratch), "shared domain air ids");
        for (int id : outside) require(id == AIR, "air is not zero");
        Fixture other = new Fixture(new DecodedSections.Section[] { null }, 0, domain);
        require(other.ids(0, scratch) == outside, "domain air reused across chunks");
        Fixture zero = new Fixture(new DecodedSections.Section[] { null }, 0, new DecodedSections.Air(0));
        require(zero.ids(0, scratch)[0] == 0 && outside[0] == AIR, "independent air domains");
    }

    private static void packed(Facing facing, boolean masksFirst) {
        int[] indices = new int[4096];
        for (int i = 0; i < indices.length; i++) indices[i] = patternIndex(i & 15, i >> 8, i >> 4 & 15);
        int[] palette = { AIR, FULL, SMALL, WATER, AIR, AIR, AIR, AIR };
        int[] originalPalette = palette.clone();
        int[] originalIndices = indices.clone();
        CountingPacked storage = new CountingPacked(indices);
        int[] columns = columns(facing);
        Fixture f = new Fixture(
            new DecodedSections.Section[] { new DecodedSections.Section(-1, null, storage, palette, columns) },
            -2,
            new DecodedSections.Air(AIR));
        int[] scratch = new int[4096];
        int[] expected = expected(facing);
        require(f.ids[0] == null && storage.calls == 0, "packed lazy initialization");
        if (masksFirst) {
            verifyMasks(f, 0, expected, SHAPES, scratch);
            require(f.ids[0] == null && storage.calls == 1, "mask-only packed path does not materialize ids");
        }
        int[] ids = f.ids(0, scratch);
        require(Arrays.equals(ids, expected), "palette ids and turned columns");
        require(ids == f.ids(0, scratch) && f.reads[0] == 1, "packed cache");
        verifyMasks(f, 0, expected, SHAPES, scratch);
        require(storage.calls == 2, "one unpack for ids and one for masks");
        require(
            Arrays.equals(indices, originalIndices) && Arrays.equals(palette, originalPalette),
            "packed inputs unchanged");
        require(f.solidTop(KINDS, SHAPES, scratch) == -16, "packed negative section top");
        for (int i = 0; i < 4096; i++) put(ids[i]);
        Fixture noShapes = new Fixture(
            new DecodedSections.Section[] {
                new DecodedSections.Section(-1, null, new CountingPacked(indices), palette, columns) },
            -2,
            new DecodedSections.Air(AIR));
        verifyMasks(noShapes, 0, expected, null, scratch);
        require(all(noShapes.partial(0), 0), "shapes-disabled partial mask");
    }

    private static void lazyByBlock(Facing facing) {
        int[] expected = expected(facing);
        Fixture f = new Fixture(
            new DecodedSections.Section[] { DecodedSections.BY_BLOCK },
            -1,
            new DecodedSections.Air(AIR));
        f.fallback = expected;
        int[] scratch = new int[4096];
        verifyMasks(f, 0, expected, SHAPES, scratch);
        require(f.ids[0] == null && f.maskFills == 1 && f.idFills == 0, "lazy large-palette mask hook");
        require(Arrays.equals(f.ids(0, scratch), expected) && f.idFills == 1, "lazy id materialization");
        require(f.ids(0, scratch) == f.ids(0, scratch) && f.idFills == 1, "fallback id cache");
        Fixture idsFirst = new Fixture(
            new DecodedSections.Section[] { DecodedSections.BY_BLOCK },
            -1,
            new DecodedSections.Air(AIR));
        idsFirst.fallback = expected;
        idsFirst.ids(0, scratch);
        verifyMasks(idsFirst, 0, expected, SHAPES, scratch);
        require(idsFirst.maskFills == 0 && idsFirst.idFills == 1, "mask reuses materialized fallback ids");
    }

    private static void explicitIds() {
        int[] ids = expected(Facing.NORTH);
        ids[0] = SMALL; // Represents an already-composed copycat look supplied by the platform.
        int[] before = ids.clone();
        Fixture f = new Fixture(
            new DecodedSections.Section[] { new DecodedSections.Section(-1, ids, null, null, null) },
            0,
            new DecodedSections.Air(AIR));
        int[] scratch = new int[4096];
        require(f.ids(0, scratch) == ids, "explicit ids retained");
        verifyMasks(f, 0, ids, SHAPES, scratch);
        require(Arrays.equals(ids, before) && f.idFills == 0, "explicit ids unchanged");
    }

    private static void composedLooks() {
        MiniLooks looks = new MiniLooks();
        int solid = looks.copycat(1, 2);
        int glass = looks.copycat(1, 3);
        require(solid == 8 && glass == 9, "real composed look ids");
        int[] ids = new int[4096];
        Arrays.fill(ids, AIR);
        ids[0] = solid;
        ids[4095] = glass;
        Fixture f = new Fixture(
            new DecodedSections.Section[] { new DecodedSections.Section(-1, ids, null, null, null) },
            0,
            new DecodedSections.Air(AIR));
        long[] mask = f.solid(0, looks.kind, looks.shape, new int[4096]);
        long[] partial = f.partial(0);
        for (int i = 0; i < 4096; i++) {
            boolean expected = i == 0;
            require(((mask[i / 64] >>> (i % 64)) & 1) == (expected ? 1 : 0), "composed solid flag");
            require(((partial[i / 64] >>> (i % 64)) & 1) == (expected ? 1 : 0), "composed partial flag");
        }
        require(f.ids(0, new int[4096]) == ids, "composed ids retained");
    }

    private static final class MiniLooks extends Looks {

        MiniLooks() {
            super(8, 10);
        }

        @Override
        protected synchronized void make(int id) {
            if ((byte) KINDS.getAcquire(kind, id) != NOT_YET) return;
            constantTint[id] = -1;
            if (id == 1) shape[id] = internShape(new float[] { 0, 0, 0, 1, 0.5F, 1 });
            publish(id, id == 1 || id == 2 ? SOLID : id == 3 ? GLASS : Looks.AIR);
        }
    }

    private static void zeroMaskIdentity() {
        int[] airIds = new int[4096];
        Arrays.fill(airIds, AIR);
        Fixture packed = new Fixture(
            new DecodedSections.Section[] {
                new DecodedSections.Section(-1, null, new CountingPacked(new int[4096]), new int[] { AIR }, null) },
            0,
            new DecodedSections.Air(AIR));
        Fixture dense = new Fixture(
            new DecodedSections.Section[] { new DecodedSections.Section(-1, airIds, null, null, null) },
            0,
            new DecodedSections.Air(AIR));
        Fixture fallback = new Fixture(
            new DecodedSections.Section[] { DecodedSections.BY_BLOCK },
            0,
            new DecodedSections.Air(AIR));
        fallback.fallback = airIds;
        for (Fixture fixture : new Fixture[] { packed, dense, fallback }) {
            long[] mask = fixture.solid(0, KINDS, SHAPES, new int[4096]);
            require(
                all(mask, 0) && mask != DecodedSections.NOTHING_SOLID && mask != DecodedSections.NONE_OF_ONE,
                "allocated zero mask identity");
        }
        Fixture zeroSolid = new Fixture(new DecodedSections.Section[] { single(0) }, 0, new DecodedSections.Air(AIR));
        require(zeroSolid.solid(0, KINDS, SHAPES, new int[4096]) == DecodedSections.ALL_SOLID, "id zero may be solid");
    }

    private static void columnPermutation() {
        int[] indices = new int[4096];
        int[] columns = new int[256];
        for (int i = 0; i < 4096; i++) indices[i] = (i * 3 + (i >> 8)) & 3;
        for (int i = 0; i < 256; i++) columns[i] = (i * 73 + 19) & 255;
        int[] palette = { AIR, FULL, SMALL, WATER };
        int[] expected = new int[4096];
        for (int y = 0; y < 16; y++)
            for (int c = 0; c < 256; c++) expected[y * 256 + c] = palette[indices[y * 256 + columns[c]]];
        Fixture f = new Fixture(
            new DecodedSections.Section[] {
                new DecodedSections.Section(-1, null, new CountingPacked(indices), palette, columns) },
            0,
            new DecodedSections.Air(AIR));
        int[] scratch = new int[4096];
        require(Arrays.equals(f.ids(0, scratch), expected), "arbitrary column permutation");
        verifyMasks(f, 0, expected, SHAPES, scratch);
    }

    private static void heightAndCaches() {
        int[] scratch = new int[4096];
        Fixture empty = new Fixture(
            new DecodedSections.Section[] { null, single(WATER) },
            -4,
            new DecodedSections.Air(AIR));
        require(empty.solidTop(KINDS, SHAPES, scratch) == Integer.MIN_VALUE, "no-solid height");
        Fixture top = new Fixture(
            new DecodedSections.Section[] { single(FULL), null, single(SMALL) },
            -4,
            new DecodedSections.Air(AIR));
        require(top.solidTop(KINDS, SHAPES, scratch) == -16, "highest solid height");
        require(top.reads[2] == 1 && top.reads[0] == 0, "height scan short circuits");
        require(top.solidTop(KINDS, SHAPES, scratch) == -16 && top.reads[2] == 1, "height cache");
        for (int at : new int[] { 0, 15, 16, 63, 64, 255, 256, 4095 }) {
            int[] ids = new int[4096];
            Arrays.fill(ids, AIR);
            ids[at] = FULL;
            Fixture sparse = new Fixture(
                new DecodedSections.Section[] { new DecodedSections.Section(-1, ids, null, null, null) },
                -3,
                new DecodedSections.Air(AIR));
            int expected = -48 + at / 256 + 1;
            require(sparse.solidTop(KINDS, SHAPES, scratch) == expected, "sparse highest-bit boundaries");
            put(expected);
        }
    }

    private static void verifyMasks(Fixture f, int index, int[] expected, int[] shapes, int[] scratch) {
        long[] solid = f.solid(index, KINDS, shapes, scratch);
        long[] partial = f.partial(index);
        require(solid == f.solid(index, KINDS, shapes, scratch), "solid cache identity");
        for (int i = 0; i < 4096; i++) {
            boolean isSolid = KINDS[expected[i]] == Looks.SOLID;
            boolean smaller = isSolid && shapes != null && shapes[expected[i]] != 0;
            require(((solid[i / 64] >>> (i % 64)) & 1) == (isSolid ? 1 : 0), "solid mask oracle");
            require(((partial[i / 64] >>> (i % 64)) & 1) == (smaller ? 1 : 0), "partial mask oracle");
        }
    }

    private static int[] expected(Facing facing) {
        int[] out = new int[4096];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            // Independent inverse quarter-turn about block centres, within one chunk.
            int wx = x;
            int wz = z;
            for (int i = 0; i < facing.turns; i++) {
                int oldX = wx;
                wx = 15 - wz;
                wz = oldX;
            }
            int p = patternIndex(wx, y, wz);
            out[(y * 16 + z) * 16 + x] = p == 0 || p >= 4 ? AIR : p == 1 ? FULL : p == 2 ? SMALL : WATER;
        }
        return out;
    }

    private static int patternIndex(int x, int y, int z) {
        return (x + 3 * z + 5 * y) & 7;
    }

    private static int[] columns(Facing facing) {
        if (facing == Facing.NORTH) return null;
        int[] out = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            out[z * 16 + x] = ((facing.worldBlockZ(x, z) & 15) << 4) | (facing.worldBlockX(x, z) & 15);
        return out;
    }

    private static DecodedSections.Section single(int id) {
        return new DecodedSections.Section(id, null, null, null, null);
    }

    private static final class CountingPacked implements DecodedSections.PackedIndices {

        private final int[] indices;
        private int calls;

        CountingPacked(int[] indices) {
            this.indices = indices;
        }

        @Override
        public void unpack(int[] out) {
            calls++;
            System.arraycopy(indices, 0, out, 0, 4096);
        }
    }

    private static final class Fixture extends DecodedSections {

        private final Section[] inputs;
        private final int[] reads;
        private int[] fallback;
        private int idFills;
        private int maskFills;

        Fixture(Section[] sections, int minSectionY, Air air) {
            super(sections.length, minSectionY, air);
            this.inputs = sections;
            this.reads = new int[sections.length];
        }

        @Override
        protected Section read(int index) {
            reads[index]++;
            return inputs[index] == null ? air.section : inputs[index];
        }

        @Override
        protected int[] byBlock(int index) {
            idFills++;
            return fallback.clone();
        }

        @Override
        protected void fillSolidByBlock(int index, byte[] kinds, int[] shapes) {
            maskFills++;
            long[] s = new long[64];
            long[] p = NOTHING_SOLID;
            // Synthetic provider oracle, not a second game-state decoding implementation.
            for (int i = 0; i < 4096; i++) if (kinds[fallback[i]] == Looks.SOLID) {
                s[i / 64] |= 1L << (i % 64);
                if (shapes != null && shapes[fallback[i]] != 0) {
                    if (p == NOTHING_SOLID) p = new long[64];
                    p[i / 64] |= 1L << (i % 64);
                }
            }
            solid[index] = s;
            partial[index] = p;
        }
    }

    private static boolean all(long[] values, long value) {
        for (long v : values) if (v != value) return false;
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void put(int value) {
        HASH.update((byte) (value >>> 24));
        HASH.update((byte) (value >>> 16));
        HASH.update((byte) (value >>> 8));
        HASH.update((byte) value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
