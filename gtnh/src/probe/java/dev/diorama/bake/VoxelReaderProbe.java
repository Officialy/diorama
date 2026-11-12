package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Actual numerical reader algorithms against prepared chunks and scalar world/caster oracles. */
public final class VoxelReaderProbe {

    private static final int AIR = 7;
    private static final int FULL = 0;
    private static final int SMALL = 2;
    private static final int WATER = 3;
    private static final byte[] KINDS = new byte[16];
    private static final int[] SHAPES = new int[16];
    private static final MessageDigest HASH = digest();

    public static void main(String[] args) {
        KINDS[FULL] = Looks.SOLID;
        KINDS[SMALL] = Looks.SOLID;
        KINDS[WATER] = Looks.WATER;
        SHAPES[SMALL] = 1;
        SHAPES[WATER] = 2;
        pinningAndCollisions();
        coordinatesAndCaches();
        sectionIdentityAndHeight();
        casterOracles();
        lights();
        roofQueries();
        StringBuilder hex = new StringBuilder();
        for (byte value : HASH.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS numerical reader: pinned chunks/misses, collisions, bounds, masks, scalar casters, light and roof queries; MC capture/numbering/GL UNTESTED");
    }

    private static void pinningAndCollisions() {
        Reader r = new Reader(-16, 16, true, true);
        int[][] chunks = { { -33, -65 }, { -1, -33 }, { 31, -1 }, { 63, 31 } };
        for (int i = 0; i < chunks.length; i++)
            r.source.put(key(chunks[i][0], chunks[i][1]), Blueprint.uniform(-1, 2, i % 2 == 0 ? FULL : SMALL));
        for (int pass = 0; pass < 3; pass++) for (int i = 0; i < chunks.length; i++) {
            int cx = chunks[i][0];
            int cz = chunks[i][1];
            int x = cx * 16 + 15;
            int z = cz * 16;
            require(r.state(x, -1, z) == (i % 2 == 0 ? FULL : SMALL), "low-bit table collision ids");
            require(r.solid(x, 0, z) == (i % 2 == 0 ? 1 : 2), "low-bit table collision masks");
            require(r.calls(cx, cz) == 1, "each colliding source queried once");
            if (pass == 0) r.source.put(key(cx, cz), Blueprint.uniform(-1, 2, AIR));
        }
        int missingX = -65;
        int missingZ = -97;
        require(r.state(missingX * 16, 0, missingZ * 16) == VoxelReaderCore.UNKNOWN, "initial missing state");
        r.source.put(key(missingX, missingZ), Blueprint.uniform(-1, 2, FULL));
        r.state(63 * 16, 0, 31 * 16); // Evict the low-bit table slot, not the job cache.
        require(r.solid(missingX * 16, 0, missingZ * 16) == VoxelReaderCore.UNKNOWN, "missing mask pinned after load");
        require(
            r.state(missingX * 16 + 1, 1, missingZ * 16 + 1) == VoxelReaderCore.UNKNOWN
                && r.calls(missingX, missingZ) == 1,
            "missing ids pinned after collision");
        require(
            r.solidSection(missingX, 0, missingZ) == null && r.partialSection(missingX, 0, missingZ) == null,
            "unknown section masks null");
        require(
            r.missing != null && r.missing.contains(key(missingX, missingZ)) && r.missing.size() == 1,
            "debug missing deduplicated");
        Reader fresh = new Reader(-16, 16, true, false);
        fresh.source.putAll(r.source);
        require(fresh.state(-33 * 16, 0, -65 * 16) == AIR, "new job sees replacement present chunk");
        require(fresh.state(missingX * 16, 0, missingZ * 16) == FULL, "new job sees loaded chunk");
        require(fresh.missing == null, "debug-disabled tracking absent");
        put(r.calls(missingX, missingZ));
    }

    private static void coordinatesAndCaches() {
        Reader r = new Reader(-32, 48, true, false);
        for (int cx = -3; cx <= 2; cx++)
            for (int cz = -3; cz <= 2; cz++) r.source.put(key(cx, cz), Blueprint.pattern(cx, cz, -2, 5));
        int[] axis = { -33, -32, -17, -16, -1, 0, 15, 16, 31, 32 };
        int[] ys = { -32, -17, -16, -1, 0, 15, 16, 31, 32, 47 };
        for (int x : axis) for (int z : axis) for (int y : ys) {
            int expected = pattern(x, y, z);
            require(r.state(x, y, z) == expected, "negative/local state indexing");
            // Interleave a different section into the independent mask cache.
            int otherY = y < 0 ? y + 32 : y - 32;
            require(r.solid(x, otherY, z) == classification(pattern(x, otherY, z), true), "interleaved mask indexing");
            require(
                r.state(x, y, z) == expected && r.solid(x, y, z) == classification(expected, true),
                "state and mask cache independence");
            put(expected);
        }
        Reader statesOnly = new Reader(-16, 16, false, false, true);
        statesOnly.source.put(key(-1, 0), Blueprint.pattern(-1, 0, -1, 2));
        require(statesOnly.state(-1, -1, 15) == pattern(-1, -1, 15), "state-only reader with no look arrays");
        Reader noShapes = new Reader(-16, 16, false, false);
        noShapes.source.put(key(0, 0), Blueprint.uniform(-1, 2, SMALL));
        require(noShapes.solid(0, 0, 0) == 1 && all(noShapes.partialSection(0, 0, 0), 0), "shapes-disabled reader");
        Reader bounds = new Reader(-32, 48, true, false);
        require(bounds.state(0, -33, 0) == AIR && bounds.state(0, 48, 0) == AIR, "out-of-world state uses nonzero air");
        require(
            bounds.solid(0, -33, 0) == 0 && bounds.solid(0, 48, 0) == 0 && bounds.totalCalls() == 0,
            "bounds avoid source reads");
    }

    private static void sectionIdentityAndHeight() {
        Reader r = new Reader(-16, 48, true, false);
        require(r.solidTop(0, 0, 0, 0) == Integer.MAX_VALUE && r.totalCalls() == 0, "unread height does not fetch");
        r.source.put(
            key(0, 0),
            new Blueprint(
                -1,
                new int[][] { null, filled(AIR), filled(SMALL), filled(FULL) },
                new boolean[] { false, true, true, true }));
        require(r.state(0, 0, 0) == AIR, "pin known column");
        long[] empty = r.solidSection(0, -1, 0);
        long[] uniformAir = r.solidSection(0, 0, 0);
        require(
            VoxelReaderCore.nothingSolid(empty) && !VoxelReaderCore.nothingSolid(uniformAir)
                && all(empty, 0)
                && all(uniformAir, 0),
            "air-vs-uniform-nonsolid mask identity");
        require(
            r.solidSection(0, -2, 0) == empty && r.partialSection(0, -2, 0) == empty,
            "out-of-range section sentinels");
        require(
            r.partialSection(0, 1, 0) == DecodedSections.ALL_SOLID
                && r.solidSection(0, 1, 0) == DecodedSections.ALL_SOLID,
            "partial section identity");
        require(r.partialSection(0, 2, 0) == empty, "full section has no partials");
        require(r.solidTop(0, 0, 0, 0) == 48, "highest pinned solid");
        int calls = r.totalCalls();
        require(
            r.solidTop(0, 0, 1, 0) == Integer.MAX_VALUE && r.totalCalls() == calls,
            "mixed unread height is conservative");
        require(r.state(16, 0, 0) == VoxelReaderCore.UNKNOWN, "pin missing neighbour for height");
        require(
            r.solidTop(0, 0, 1, 0) == 48 && r.solidTop(1, 0, 1, 0) == Integer.MIN_VALUE,
            "known misses excluded from top");
        Reader sparse = new Reader(-32, 32, true, false);
        int[][] cells = { filled(AIR), filled(AIR), filled(AIR), filled(AIR) };
        cells[2][63] = FULL; // y=0 in section2, bit63 deliberately high in its word.
        sparse.source.put(key(-1, -1), new Blueprint(-2, cells, new boolean[4]));
        sparse.state(-1, 0, -1);
        require(sparse.solidTop(-1, -1, -1, -1) == 1, "sparse negative-section top");
        put(r.solidTop(0, 0, 1, 0));
    }

    private static void casterOracles() {
        for (boolean shapes : new boolean[] { true, false }) for (boolean pattern : new boolean[] { false, true }) {
            casterCase(shapes, pattern, false, false);
            casterCase(shapes, pattern, true, false);
            casterCase(shapes, pattern, true, true);
        }
        // A cached conservative caster mask intentionally stays conservative if neighbours are read later.
        Reader r = casterReader(true, false, false);
        long[] before = r.casterSection(-1, -1, -2);
        require(
            r.totalCalls() == 1 && r.calls(-2, -2) == 0 && r.calls(-1, -1) == 0,
            "casters do not fetch unread side neighbours");
        verifyCaster(r, before, -1, -1, -2, true, false, false);
        long[] saved = before.clone();
        r.fetch(-2, -2);
        r.fetch(-1, -1);
        require(
            r.casterSection(-1, -1, -2) == before && Arrays.equals(saved, before),
            "caster mask cached across later neighbour reads");
        require(r.casterSection(-1, -2, -2) == null && r.casterSection(-1, 1, -2) == null, "caster section bounds");
        require(r.casterSection(10, 0, 10) == null, "missing chunk caster mask");
    }

    private static void casterCase(boolean shapes, boolean patterned, boolean neighbours, boolean shortWest) {
        Reader r = casterReader(shapes, patterned, shortWest);
        if (neighbours) {
            r.fetch(-2, -2);
            r.fetch(-1, -1);
            // Direct table collisions force peek to find pinned neighbours in the job map.
            r.source.put(key(30, 30), Blueprint.uniform(-1, 2, AIR));
            r.source.put(key(31, 31), Blueprint.uniform(-1, 2, AIR));
            r.fetch(30, 30);
            r.fetch(31, 31);
        }
        int prior = r.totalCalls();
        for (int sy = -1; sy <= 0; sy++) {
            long[] mask = r.casterSection(-1, sy, -2);
            require(mask != null && mask == r.casterSection(-1, sy, -2), "caster identity cache");
            verifyCaster(r, mask, -1, sy, -2, shapes, neighbours, shortWest);
            for (long word : mask) {
                put((int) word);
                put((int) (word >>> 32));
            }
        }
        require(r.totalCalls() == prior + 1, "caster only fetches its own unread chunk");
    }

    private static Reader casterReader(boolean shapes, boolean patterned, boolean shortWest) {
        Reader r = new Reader(-16, 16, shapes, false);
        for (int[] p : new int[][] { { -1, -2 }, { -2, -2 }, { -1, -1 } }) {
            int min = shortWest && p[0] == -2 ? 0 : -1;
            int count = min == 0 ? 1 : 2;
            r.source.put(
                key(p[0], p[1]),
                patterned ? Blueprint.pattern(p[0], p[1], min, count) : Blueprint.uniform(min, count, FULL));
        }
        return r;
    }

    private static void verifyCaster(Reader r, long[] mask, int cx, int sy, int cz, boolean shapes, boolean neighbours,
        boolean shortWest) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int wx = cx * 16 + x;
            int wy = sy * 16 + y;
            int wz = cz * 16 + z;
            int id = supplied(r, wx, wy, wz, true, true, shortWest);
            boolean solid = id == FULL || id == SMALL;
            boolean partial = shapes && id == SMALL;
            boolean covered = full(supplied(r, wx, wy + 1, wz, true, true, shortWest), shapes)
                && full(supplied(r, wx - 1, wy, wz, neighbours, true, shortWest), shapes)
                && full(supplied(r, wx, wy, wz + 1, true, neighbours, shortWest), shapes);
            boolean expected = solid && (partial || !covered);
            int index = y * 256 + z * 16 + x;
            require(
                ((mask[index / 64] >>> (index % 64)) & 1) == (expected ? 1 : 0),
                "independent scalar caster oracle");
        }
    }

    private static int supplied(Reader r, int x, int y, int z, boolean westKnown, boolean southKnown,
        boolean shortWest) {
        int cx = Math.floorDiv(x, 16);
        int cz = Math.floorDiv(z, 16);
        if (cx == -2 && !westKnown || cz == -1 && !southKnown) return AIR;
        Blueprint p = r.source.get(key(cx, cz));
        if (p == null) return AIR;
        int section = Math.floorDiv(y, 16) - p.minSectionY;
        if (section < 0 || section >= p.cells.length) return AIR;
        int[] cells = p.cells[section];
        return cells == null ? AIR
            : cells[(Math.floorMod(y, 16) * 16 + Math.floorMod(z, 16)) * 16 + Math.floorMod(x, 16)];
    }

    private static boolean full(int id, boolean shapes) {
        return id == FULL || id == SMALL && !shapes;
    }

    private static void lights() {
        Reader r = new Reader(-32, 48, true, false);
        r.source.put(key(-2, -3), Blueprint.uniform(-2, 5, AIR));
        int[][] points = { { -17, -32, -33 }, { -32, -17, -48 }, { -31, -1, -47 }, { -20, 0, -42 }, { -18, 47, -34 } };
        for (int[] p : points) {
            int section = Math.floorDiv(p[1], 16) + 2;
            int lx = Math.floorMod(p[0], 16), ly = Math.floorMod(p[1], 16), lz = Math.floorMod(p[2], 16);
            require(r.blockLight(p[0], p[1], p[2]) == blockValue(section, lx, ly, lz), "block light coordinates");
            require(
                Arrays.equals(r.lastLight, new int[] { section, lx, ly, lz })
                    && r.lastLightChunk == r.loaded.get(key(-2, -3)),
                "block light target chunk");
            require(r.skyLight(p[0], p[1], p[2]) == skyValue(section, lx, ly, lz), "sky light coordinates");
            require(
                Arrays.equals(r.lastLight, new int[] { section, lx, ly, lz })
                    && r.lastLightChunk == r.loaded.get(key(-2, -3)),
                "sky light target chunk");
        }
        int invoked = r.lightReads;
        require(r.blockLight(0, -33, 0) == 0 && r.blockLight(0, 48, 0) == 0, "block light world bounds");
        require(
            r.skyLight(0, -33, 0) == 0 && r.skyLight(0, 48, 0) == 15 && r.lightReads == invoked,
            "sky light world bounds");
        require(
            r.blockLight(0, 0, 0) == 0 && r.skyLight(0, 0, 0) == 15 && r.calls(0, 0) == 1,
            "missing light defaults and pinning");
        r.source.put(key(0, 0), Blueprint.uniform(-2, 5, FULL));
        require(
            r.skyLight(0, 1, 0) == 15 && r.blockLight(0, 1, 0) == 0 && r.lightReads == invoked,
            "missing light remains pinned");
    }

    private static void roofQueries() {
        Reader r = new Reader(0, 32, true, false);
        int[][] cells = { filled(AIR), filled(AIR) };
        for (int y = 10; y < 16; y++) set(cells, 2, y, 3, y == 12 ? AIR : FULL);
        set(cells, 2, 1, 3, SMALL);
        set(cells, 2, 3, 3, FULL);
        r.source.put(key(0, 0), new Blueprint(0, cells, new boolean[2]));
        require(r.pocket(2, 12, 3) && r.pocket(2, 2, 3), "single cell pockets, partial counts solid");
        require(!r.pocket(2, 9, 3) && !r.pocket(2, 0, 3) && !r.pocket(32, 4, 32), "open, boundary and unknown pockets");
        require(r.lid(2, 3, 16, 0) == 10, "lid skips buried pocket");
        require(r.lid(2, 3, 13, 0) == 13, "open cell just under cut ends lid even if pocket");
        require(r.lid(2, 3, 16, 12) == 12, "lid limit");
        require(
            r.ceilingUnderside(2, 10, 3, 10, 0) && r.ceilingUnderside(2, 11, 3, 10, 0),
            "ceiling bottom two blocks");
        require(
            !r.ceilingUnderside(2, 12, 3, 10, 0) && !r.ceilingUnderside(2, 9, 3, 10, 0),
            "ceiling outside underside band");
        require(!r.ceilingUnderside(2, 12, 3, 12, 12), "lid at limit not underside");
        require(!r.ceilingUnderside(32, 4, 32, 4, 0), "unknown below is not open underside");
        put(r.lid(2, 3, 16, 0));
    }

    private static int classification(int id, boolean shapes) {
        return id == FULL ? 1 : id == SMALL ? shapes ? 2 : 1 : 0;
    }

    private static int pattern(int x, int y, int z) {
        int n = Math.floorMod(x * 3 + y * 5 + z * 7, 11);
        return n < 5 ? FULL : n < 7 ? SMALL : n == 7 ? WATER : AIR;
    }

    private static int[] filled(int id) {
        int[] out = new int[4096];
        Arrays.fill(out, id);
        return out;
    }

    private static void set(int[][] cells, int x, int y, int z, int id) {
        cells[y / 16][(y % 16 * 16 + z) * 16 + x] = id;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int blockValue(int s, int x, int y, int z) {
        return (s * 3 + x + y * 2 + z) & 15;
    }

    private static int skyValue(int s, int x, int y, int z) {
        return (s + x + y * 3 + z * 2) & 15;
    }

    private static final class Blueprint {

        private final int minSectionY;
        private final int[][] cells;
        private final boolean[] uniform;

        Blueprint(int minSectionY, int[][] cells, boolean[] uniform) {
            this.minSectionY = minSectionY;
            this.cells = cells;
            this.uniform = uniform;
        }

        static Blueprint uniform(int minSectionY, int count, int id) {
            int[][] out = new int[count][];
            boolean[] single = new boolean[count];
            for (int i = 0; i < count; i++) {
                out[i] = filled(id);
                single[i] = true;
            }
            return new Blueprint(minSectionY, out, single);
        }

        static Blueprint pattern(int cx, int cz, int minSectionY, int count) {
            int[][] out = new int[count][4096];
            for (int s = 0; s < count; s++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) out[s][y * 256 + z * 16 + x] = VoxelReaderProbe
                    .pattern(cx * 16 + x, (minSectionY + s) * 16 + y, cz * 16 + z);
            return new Blueprint(minSectionY, out, new boolean[count]);
        }
    }

    private static final class Chunk extends DecodedSections {

        private final Blueprint blueprint;
        private int reads;

        Chunk(Blueprint p) {
            super(p.cells.length, p.minSectionY, new Air(AIR));
            blueprint = p;
        }

        @Override
        protected Section read(int index) {
            reads++;
            int[] cells = blueprint.cells[index];
            return cells == null ? air.section
                : blueprint.uniform[index] ? new Section(cells[0], null, null, null, null)
                    : new Section(-1, cells, null, null, null);
        }

        @Override
        protected int[] byBlock(int index) {
            throw new AssertionError("unexpected fallback ids");
        }

        @Override
        protected void fillSolidByBlock(int index, byte[] kinds, int[] shapes) {
            throw new AssertionError("unexpected fallback masks");
        }
    }

    private static final class Reader extends VoxelReaderCore {

        private final Map<Long, Blueprint> source = new HashMap<>();
        private final Map<Long, Integer> requests = new HashMap<>();
        private final Map<Long, Chunk> loaded = new HashMap<>();
        private int lightReads;
        private int[] lastLight;
        private DecodedSections lastLightChunk;

        Reader(int minY, int maxY, boolean shapes, boolean debug) {
            this(minY, maxY, shapes, debug, false);
        }

        Reader(int minY, int maxY, boolean shapes, boolean debug, boolean stateOnly) {
            super(minY, maxY, AIR, stateOnly ? null : KINDS, shapes ? SHAPES : null, debug);
        }

        @Override
        protected DecodedSections loadChunk(int cx, int cz) {
            long k = VoxelReaderProbe.key(cx, cz);
            requests.put(k, calls(cx, cz) + 1);
            Blueprint p = source.get(k);
            Chunk chunk = p == null ? null : new Chunk(p);
            if (chunk != null) loaded.put(k, chunk);
            return chunk;
        }

        @Override
        protected int readBlockLight(DecodedSections chunk, int section, int x, int y, int z) {
            noteLight(chunk, section, x, y, z);
            return blockValue(section, x, y, z);
        }

        @Override
        protected int readSkyLight(DecodedSections chunk, int section, int x, int y, int z) {
            noteLight(chunk, section, x, y, z);
            return skyValue(section, x, y, z);
        }

        private void noteLight(DecodedSections chunk, int s, int x, int y, int z) {
            lightReads++;
            lastLight = new int[] { s, x, y, z };
            lastLightChunk = chunk;
        }

        private int calls(int cx, int cz) {
            Integer value = requests.get(VoxelReaderProbe.key(cx, cz));
            return value == null ? 0 : value;
        }

        private int totalCalls() {
            int count = 0;
            for (int n : requests.values()) count += n;
            return count;
        }

        private void fetch(int cx, int cz) {
            chunk(cx, cz);
        }
    }

    private static boolean all(long[] values, long value) {
        for (long v : values) if (v != value) return false;
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void put(int value) {
        HASH.update((byte) (value >>> 24));
        HASH.update((byte) (value >>> 16));
        HASH.update((byte) (value >>> 8));
        HASH.update((byte) value);
    }
}
