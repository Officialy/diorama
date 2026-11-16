package dev.diorama.gtnh.probe;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import dev.diorama.bake.Looks;
import dev.diorama.bake.VoxelReader;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;

/** Real capture orchestration against borrowed numeric views, without Minecraft or fake game types. */
public final class CaptureProbe {

    private static final int AIR = 7;
    private static final int[] RAW = { 0, 255, 256, 4095, 65535 };
    private static final MessageDigest HASH = digest();
    private static int assertions;

    public static void main(String[] args) {
        readiness();
        guards();
        completeCapture(true);
        completeCapture(false);
        extendedMetadata();
        knownEmpty();
        pending();
        stale();
        invalid();
        StringBuilder hex = new StringBuilder();
        for (byte b : HASH.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS capture: " + assertions
                + " assertions; copied IDs/metadata/coordinates/light/height, readiness, source-thread/session guards; native bridge/game/appearance preparation UNTESTED");
    }

    private static void readiness() {
        TestLooks looks = new TestLooks();
        require(looks.isPrepared(0) && looks.isPrepared(AIR), "published solid zero and nonzero air");
        require(!looks.isPrepared(-1) && !looks.isPrepared(looks.kind.length), "readiness bounds");
        require(!looks.isPrepared(6) && !looks.isPrepared(8), "unpublished allocated IDs not ready");
        require(looks.top[0] == 0x123456 && looks.makeCalls == 0, "published payload readable without make");
        looks.ready(8, Looks.GLASS);
        require(looks.isPrepared(8) && looks.makeCalls == 0, "explicit publication changes readiness only");
        put(looks.top[0]);
    }

    private static void guards() {
        Fixture wrongThread = new Fixture(1, true);
        wrongThread.onThread = false;
        expect(IllegalStateException.class, () -> wrongThread.capture(), "wrong thread rejected");
        require(
            wrongThread.calls.size() == 1 && wrongThread.count("onThread") == 1,
            "wrong thread touches only onThread");
        Fixture missing = new Fixture(1, true);
        missing.missing = true;
        ChunkCapture.Result absent = missing.capture();
        status(absent, ChunkCapture.Status.MISSING_CHUNK, missing.epoch);
        require(
            missing.count("loaded") == 1 && missing.count("section") == 0,
            "loaded-only missing lookup, no section borrow");
        require(
            missing.count("looks") == 0 && missing.count("look") == 0 && missing.count("tints") == 0,
            "missing does not ask appearance");
        Fixture mismatch = new Fixture(1, true);
        mismatch.inputEpoch = new Object();
        status(mismatch.capture(), ChunkCapture.Status.STALE_SESSION, mismatch.epoch);
        require(mismatch.count("loaded") == 0, "session mismatch precedes chunk access");
        Fixture disconnected = new Fixture(1, true);
        disconnected.connected = false;
        status(disconnected.capture(), ChunkCapture.Status.STALE_SESSION, null);
        require(
            disconnected.count("loaded") == 0 && disconnected.count("accessSession") == 0,
            "already stale avoids world and epoch access");
    }

    private static void completeCapture(boolean sky) {
        Fixture f = new Fixture(16, sky);
        ChunkCapture.Result result = f.capture();
        status(result, ChunkCapture.Status.READY, f.epoch);
        ChunkSnapshot snapshot = result.snapshot();
        require(
            snapshot.chunkX == f.cx && snapshot.chunkZ == f.cz && snapshot.minSectionY == f.minSection,
            "captured coordinates and vertical origin");
        require(
            snapshot.sectionCount() == 16 && snapshot.airId() == AIR && snapshot.lookDomain() == f.looks,
            "full 256-height explicit domain");
        int[] surfaces = new int[256];
        Arrays.fill(surfaces, Integer.MIN_VALUE);
        int maximum = Integer.MIN_VALUE;
        int expectedLookCalls = 0;
        int contextualA = 0;
        int contextualB = 0;
        for (int si = 0; si < 16; si++) {
            int[] ids = snapshot.sectionIds(si);
            require((ids == null) == f.nullSections[si], "null section stays known air");
            for (int cell = 0; cell < 4096; cell++) {
                int x = cell & 15;
                int z = (cell >>> 4) & 15;
                int y = cell >>> 8;
                int worldY = (f.minSection + si) * 16 + y;
                int expected = f.nullSections[si] ? AIR
                    : resolve(f.raw[si][cell], f.meta[si][cell], f.cx * 16 + x, worldY, f.cz * 16 + z);
                int actual = ids == null ? AIR : ids[ids.length == 1 ? 0 : cell];
                require(actual == expected, "resolved dense ID, including raw >4095 and contextual variants");
                if (!f.nullSections[si]) expectedLookCalls++;
                if (expected == 4) contextualA++;
                if (expected == 5) contextualB++;
                if (material(expected) != Looks.AIR) {
                    surfaces[cell & 255] = Math.max(surfaces[cell & 255], worldY);
                    maximum = Math.max(maximum, worldY);
                }
                int expectedBlock = block(si, cell);
                int expectedSky = sky ? skylight(si, cell) : 0;
                require(
                    snapshot.blockLight(si, x, y, z) == expectedBlock,
                    "exact captured block light including null section");
                require(snapshot.skyLight(si, x, y, z) == expectedSky, "exact sky capture or no-sky zero");
                put(actual);
                put(expectedBlock);
                put(expectedSky);
            }
        }
        require(
            f.count("look") == expectedLookCalls && f.count("blockId") == expectedLookCalls
                && f.count("metadata") == expectedLookCalls,
            "one resolver call per non-null cell");
        require(
            f.count("blockLight") == 16 * 4096 && f.count("skyLight") == (sky ? 16 * 4096 : 0),
            "all-section light copied, no sky callback in Nether");
        require(
            contextualA > 0 && contextualB > 0 && f.rawMask == 31,
            "high raw-ID set and contextual variants exercised");
        require(snapshot.maxY == maximum && maximum >= 128, "non-air height includes upper half of 256-high dimension");
        for (int column = 0; column < 256; column++) {
            require(
                snapshot.surface(column & 15, column >>> 4) == surfaces[column],
                "column top uses resolved kind, not opacity or raw zero");
            put(surfaces[column]);
        }
        require(snapshot.fogColor(-999, 100, 999) == 0x456789, "prepared fog RGB strips alpha");
        for (int i = 0; i < 256; i++) {
            require(
                snapshot.bakeTints()
                    .grass()[i] == 0x100000 + i,
                "grass copied exactly");
            require(
                snapshot.bakeTints()
                    .foliage()[i] == 0x200000 + i
                    && snapshot.bakeTints()
                        .dryFoliage()[i] == 0x300000 + i
                    && snapshot.bakeTints()
                        .water()[i] == 0x400000 + i,
                "all four tint channels copied");
        }
        int beforeId = snapshot.sectionIds(0)[0];
        int beforeBlock = snapshot.blockLight(1, 2, 3, 4);
        int beforeSky = snapshot.skyLight(1, 2, 3, 4);
        for (int si = 0; si < 16; si++) {
            Arrays.fill(f.raw[si], -9);
            Arrays.fill(f.meta[si], 99);
            Arrays.fill(f.blocks[si], 0);
            Arrays.fill(f.skies[si], 0);
        }
        Arrays.fill(f.tint.grass(), -1);
        Arrays.fill(f.tint.foliage(), -1);
        Arrays.fill(f.tint.dryFoliage(), -1);
        Arrays.fill(f.tint.water(), -1);
        f.connected = false;
        f.epoch = new Object();
        require(
            snapshot.sectionIds(0)[0] == beforeId && snapshot.surface(0, 0) == surfaces[0],
            "READY detached from borrowed source mutation");
        require(
            snapshot.blockLight(1, 2, 3, 4) == beforeBlock && snapshot.skyLight(1, 2, 3, 4) == beforeSky,
            "captured lights survive input mutation");
        require(
            snapshot.bakeTints()
                .grass()[0] == 0x100000
                && snapshot.bakeTints()
                    .water()[255] == 0x4000ff,
            "READY detached from retained tint DTO");
        require(result.session() != f.epoch, "opaque result epoch enables later stale acceptance rejection");
        int[] escaped = snapshot.sectionIds(0);
        escaped[0] = AIR;
        require(snapshot.sectionIds(0)[0] == beforeId, "section accessor does not expose snapshot storage");
        VoxelReader reader = reader(snapshot, f.looks);
        require(
            reader.state(f.cx * 16, f.minSection * 16, f.cz * 16) == beforeId,
            "captured snapshot feeds actual shared reader");
        require(
            reader.state((f.cx + 1) * 16, f.minSection * 16, f.cz * 16) == VoxelReader.UNKNOWN,
            "missing adjacent source is UNKNOWN, not captured air");
        require(
            reader.state(f.cx * 16, (f.minSection + 1) * 16, f.cz * 16) == AIR,
            "known-null captured section is air");
        require(f.looks.makeCalls == 0, "capture and prepared reader never prepare looks");
        put(maximum);
    }

    private static void knownEmpty() {
        Fixture empty = new Fixture(16, false);
        Arrays.fill(empty.nullSections, true);
        ChunkSnapshot allAir = empty.capture()
            .snapshot();
        require(allAir != null && allAir.maxY == Integer.MIN_VALUE, "loaded all-air chunk is READY with empty height");
        require(
            empty.count("look") == 0 && empty.count("blockLight") == 65536,
            "known empty still captures all Nether light");
        for (int c = 0; c < 256; c++)
            require(allAir.surface(c & 15, c >>> 4) == Integer.MIN_VALUE, "all-air column sentinel");
        Fixture alternate = new Fixture(1, true);
        Arrays.fill(alternate.raw[0], 255);
        ChunkSnapshot alt = alternate.capture()
            .snapshot();
        require(
            alt.maxY == Integer.MIN_VALUE && alt.sectionIds(0).length == 1 && alt.sectionIds(0)[0] == 3,
            "alternate AIR kind determines height and preserves uniform ID");
        Fixture negative = new Fixture(2, true);
        negative.minSection = -2;
        ChunkSnapshot below = negative.capture()
            .snapshot();
        require(below.minSectionY == -2 && below.maxY == -17, "negative section origin retains negative highest block");
        require(
            reader(below, negative.looks).state(negative.cx * 16, -32, negative.cz * 16) == 0,
            "negative world y reaches reader without rebasing");
        Fixture transparent = new Fixture(1, true);
        Arrays.fill(transparent.raw[0], 255);
        transparent.raw[0][(15 << 8) | 17] = 4095;
        transparent.raw[0][(14 << 8) | 18] = 256;
        ChunkSnapshot glass = transparent.capture()
            .snapshot();
        require(
            glass.maxY == 15 && glass.surface(1, 1) == 15 && glass.surface(2, 1) == 14,
            "glass and water count as non-air height");
        put(glass.maxY);
    }

    private static void pending() {
        Fixture unresolved = new Fixture(1, true);
        unresolved.overrideLook = -1;
        status(unresolved.capture(), ChunkCapture.Status.PENDING_APPEARANCE, unresolved.epoch);
        require(
            unresolved.count("look") == 1 && unresolved.looks.makeCalls == 0,
            "negative unresolved ID never becomes air or triggers make");
        Fixture unready = new Fixture(1, true);
        unready.overrideLook = 6;
        status(unready.capture(), ChunkCapture.Status.PENDING_APPEARANCE, unready.epoch);
        require(
            unready.looks.makeCalls == 0 && !unready.looks.isPrepared(6),
            "unready allocated ID is not lazily prepared");
        Fixture tint = new Fixture(1, true);
        tint.tint = null;
        status(tint.capture(), ChunkCapture.Status.PENDING_APPEARANCE, tint.epoch);
        require(tint.count("fog") == 0 && tint.looks.makeCalls == 0, "no fake white tint success");
        Fixture air = new Fixture(1, true);
        air.air = 8;
        status(air.capture(), ChunkCapture.Status.PENDING_APPEARANCE, air.epoch);
        require(air.count("section") == 0 && air.looks.makeCalls == 0, "unready air aborts before section access");
    }

    private static void stale() {
        String[] stages = { "accessSession", "loaded", "chunkX", "chunkZ", "minSection", "sectionCount", "hasSky",
            "looks", "air", "section", "blockId", "metadata", "look", "blockLight", "skyLight", "tints", "fog" };
        for (String stage : stages) {
            Fixture disconnected = new Fixture(1, true);
            disconnected.trigger = stage;
            disconnected.action = () -> disconnected.connected = false;
            status(disconnected.capture(), ChunkCapture.Status.STALE_SESSION, disconnected.originalEpoch);
            require(disconnected.count(stage) > 0, "invalidation callback reached");
            Fixture generation = new Fixture(1, true);
            generation.trigger = stage;
            generation.action = () -> generation.epoch = new Object();
            status(
                generation.capture(),
                ChunkCapture.Status.STALE_SESSION,
                stage.equals("accessSession") ? generation.epoch : generation.originalEpoch);
            require(generation.count(stage) > 0, "generation callback reached");
            put(stage.length());
        }
        Fixture missingInputSession = new Fixture(1, true);
        missingInputSession.missing = true;
        missingInputSession.trigger = "loaded";
        missingInputSession.action = () -> missingInputSession.inputEpoch = new Object();
        status(missingInputSession.capture(), ChunkCapture.Status.STALE_SESSION, missingInputSession.originalEpoch);
        Fixture inputSession = new Fixture(1, true);
        inputSession.trigger = "look";
        inputSession.action = () -> inputSession.inputEpoch = new Object();
        status(inputSession.capture(), ChunkCapture.Status.STALE_SESSION, inputSession.originalEpoch);
        Fixture lookGeneration = new Fixture(1, true);
        lookGeneration.trigger = "look";
        lookGeneration.action = () -> lookGeneration.looks = new TestLooks();
        status(lookGeneration.capture(), ChunkCapture.Status.STALE_SESSION, lookGeneration.originalEpoch);
        Fixture airGeneration = new Fixture(1, true);
        airGeneration.trigger = "tints";
        airGeneration.action = () -> airGeneration.air = 3;
        status(airGeneration.capture(), ChunkCapture.Status.STALE_SESSION, airGeneration.originalEpoch);
        Fixture pendingStale = new Fixture(1, true);
        pendingStale.overrideLook = -1;
        pendingStale.trigger = "look";
        pendingStale.action = () -> pendingStale.inputEpoch = new Object();
        status(pendingStale.capture(), ChunkCapture.Status.STALE_SESSION, pendingStale.originalEpoch);
    }

    private static void extendedMetadata() {
        int[] rawIds = { 65536, 0x00ffffff, 0x01000000, 0x01000001, Integer.MAX_VALUE };
        int[] metadata = { 16, 255, 256, 65535 };
        for (int raw : rawIds) {
            for (int meta : metadata) {
                Fixture f = new Fixture(1, true);
                Arrays.fill(f.raw[0], raw);
                Arrays.fill(f.meta[0], meta);
                f.overrideLook = 0;
                ChunkCapture.Result result = f.capture();
                status(result, ChunkCapture.Status.READY, f.epoch);
                require(f.count("look") == 4096, "every extended raw ID and metadata reaches resolver unchanged");
                require(
                    result.snapshot()
                        .sectionIds(0)[0] == 0,
                    "extended values resolve to published solid zero");
                put(raw);
                put(meta);
            }
        }
        for (int[] key : new int[][] { { -1, 0 }, { 1, -1 }, { 1, 65536 }, { 1, Integer.MAX_VALUE } }) {
            Fixture f = new Fixture(1, true);
            f.raw[0][0] = key[0];
            f.meta[0][0] = key[1];
            expect(IllegalArgumentException.class, () -> f.capture(), "invalid extended key rejected");
            require(f.count("look") == 0, "invalid key rejected before resolver");
        }
    }

    private static void invalid() {
        reject(f -> f.actualX++, "wrong chunk x");
        reject(f -> f.actualZ--, "wrong chunk z");
        reject(f -> f.reportedCount = 0, "empty vertical span");
        reject(f -> f.reportedCount = -1, "negative vertical span");
        reject(f -> f.minSection = Integer.MIN_VALUE, "vertical origin overflow");
        reject(f -> f.minSection = Integer.MAX_VALUE, "vertical end overflow");
        reject(f -> {
            f.cx = Integer.MAX_VALUE;
            f.actualX = f.cx;
        }, "x block origin overflow");
        reject(f -> {
            f.cz = Integer.MIN_VALUE;
            f.actualZ = f.cz;
        }, "z block origin overflow");
        reject(f -> f.raw[0][0] = -1, "negative raw ID");
        reject(f -> f.meta[0][0] = -1, "negative metadata");
        reject(f -> f.meta[0][0] = 65536, "metadata above unsigned short");
        reject(f -> f.overrideLook = 100, "resolved look outside allocation");
        reject(f -> f.air = -1, "negative air");
        reject(f -> f.air = 0, "air resolves to solid");
        reject(f -> f.blocks[0][0] = 16, "block light too large");
        reject(f -> f.blocks[0][0] = -1, "negative block light");
        reject(f -> f.skies[0][0] = 16, "sky light too large");
        reject(f -> f.skies[0][0] = -1, "negative sky light");
        reject(f -> {
            f.looks.ready(9, (byte) 99);
            f.overrideLook = 9;
        }, "invalid published material");
        expect(
            IllegalArgumentException.class,
            () -> new ChunkCapture.Result(ChunkCapture.Status.READY, null, new Object()),
            "READY requires snapshot");
        Fixture f = new Fixture(1, true);
        ChunkSnapshot snapshot = f.capture()
            .snapshot();
        expect(
            IllegalArgumentException.class,
            () -> new ChunkCapture.Result(ChunkCapture.Status.MISSING_CHUNK, snapshot, f.epoch),
            "non-ready must not carry snapshot");
        expect(
            IllegalArgumentException.class,
            () -> new ChunkCapture.Result(ChunkCapture.Status.READY, snapshot, null),
            "READY requires opaque epoch");
    }

    private static void reject(Consumer<Fixture> change, String message) {
        Fixture f = new Fixture(1, true);
        change.accept(f);
        expect(IllegalArgumentException.class, () -> f.capture(), message);
        require(f.looks.makeCalls == 0, "invalid input never invokes make");
    }

    private static VoxelReader reader(ChunkSnapshot s, TestLooks looks) {
        VoxelReader.Inputs source = new VoxelReader.Inputs() {

            @Override
            public int airId() {
                return AIR;
            }

            @Override
            public Object lookDomain() {
                return looks;
            }

            @Override
            public ChunkSnapshot chunk(int x, int z) {
                return x == s.chunkX && z == s.chunkZ ? s : null;
            }

            @Override
            public int minY() {
                return s.minSectionY * 16;
            }

            @Override
            public int maxY() {
                return (s.minSectionY + s.sectionCount()) * 16;
            }

            @Override
            public int topY() {
                return s.maxY + 1;
            }

            @Override
            public boolean isSeen(int x, int y, int z) {
                return true;
            }
        };
        return new VoxelReader(source, looks, true);
    }

    private static int resolve(int raw, int metadata, int x, int y, int z) {
        return switch (raw) {
            case 0 -> 0;
            case 255 -> 3;
            case 256 -> 2;
            case 4095 -> 1;
            case 65535 -> 4 + ((x ^ y ^ z ^ metadata) & 1);
            default -> throw new AssertionError("Unexpected raw fixture ID " + raw);
        };
    }

    private static byte material(int id) {
        return switch (id) {
            case 0, 4, 5 -> Looks.SOLID;
            case 1 -> Looks.GLASS;
            case 2 -> Looks.WATER;
            case 3, AIR -> Looks.AIR;
            default -> throw new AssertionError("Unexpected fixture look " + id);
        };
    }

    private static int block(int si, int cell) {
        return (si * 3 + (cell & 15) + ((cell >>> 4) & 15) * 2 + (cell >>> 8) * 5) & 15;
    }

    private static int skylight(int si, int cell) {
        return (15 - si + (cell & 15) * 3 + ((cell >>> 4) & 15) + (cell >>> 8) * 7) & 15;
    }

    private static final class TestLooks extends Looks {

        private int makeCalls;

        private TestLooks() {
            super(12, 12);
            this.top[0] = 0x123456;
            for (int id : new int[] { 0, 1, 2, 3, 4, 5, CaptureProbe.AIR }) ready(id, material(id));
        }

        private void ready(int id, byte kind) {
            publish(id, kind);
        }

        @Override
        protected void make(int id) {
            makeCalls++;
            throw new AssertionError("Capture must never make look " + id);
        }
    }

    private static final class Fixture implements ChunkCapture.Chunk {

        private final Object originalEpoch = new Object();
        private Object epoch = originalEpoch;
        private Object inputEpoch = originalEpoch;
        private TestLooks looks = new TestLooks();
        private int cx = -3, cz = -5, actualX = -3, actualZ = -5, minSection, air = AIR;
        private int reportedCount;
        private final boolean sky;
        private boolean onThread = true, connected = true, missing;
        private final int[][] raw, meta, blocks, skies;
        private final boolean[] nullSections;
        private ChunkSnapshot.Tints tint;
        private int overrideLook = Integer.MIN_VALUE;
        private int rawMask;
        private final Map<String, Integer> calls = new HashMap<>();
        private String trigger;
        private Runnable action;

        private Fixture(int count, boolean sky) {
            this.reportedCount = count;
            this.sky = sky;
            raw = new int[count][4096];
            meta = new int[count][4096];
            blocks = new int[count][4096];
            skies = new int[count][4096];
            nullSections = new boolean[count];
            for (int si = 0; si < count; si++) {
                nullSections[si] = si == 1 || si == 8 || si == 15;
                for (int cell = 0; cell < 4096; cell++) {
                    raw[si][cell] = RAW[(si * 4096 + cell) % RAW.length];
                    meta[si][cell] = (si + cell * 3) & 15;
                    blocks[si][cell] = block(si, cell);
                    skies[si][cell] = skylight(si, cell);
                }
            }
            int[][] channels = new int[4][256];
            for (int c = 0; c < 4; c++) for (int i = 0; i < 256; i++) channels[c][i] = (c + 1) * 0x100000 + i;
            tint = new ChunkSnapshot.Tints(channels[0], channels[1], channels[2], channels[3]);
        }

        private int count(String key) {
            return calls.getOrDefault(key, 0);
        }

        private void touch(String key) {
            calls.put(key, count(key) + 1);
            if (key.equals(trigger) && count(key) == 1 && action != null) action.run();
        }

        private ChunkCapture.Result capture() {
            return ChunkCapture.capture(access, inputs, cx, cz);
        }

        private final ChunkCapture.Access access = new ChunkCapture.Access() {

            @Override
            public boolean onThread() {
                touch("onThread");
                return onThread;
            }

            @Override
            public boolean current() {
                touch("current");
                return connected;
            }

            @Override
            public Object session() {
                touch("accessSession");
                return epoch;
            }

            @Override
            public ChunkCapture.Chunk loaded(int x, int z) {
                touch("loaded");
                require(x == cx && z == cz, "loaded-only request coordinates");
                return missing ? null : Fixture.this;
            }
        };
        private final ChunkCapture.Inputs inputs = new ChunkCapture.Inputs() {

            @Override
            public Object session() {
                touch("inputSession");
                return inputEpoch;
            }

            @Override
            public Looks looks() {
                touch("looks");
                return looks;
            }

            @Override
            public int airId() {
                touch("air");
                return air;
            }

            @Override
            public int lookId(int id, int metadata, int x, int y, int z) {
                touch("look");
                int si = (y >> 4) - minSection;
                int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                require(
                    x >= cx * 16 && x < cx * 16 + 16 && z >= cz * 16 && z < cz * 16 + 16,
                    "negative chunk local-to-world translation");
                require(
                    id == raw[si][cell] && metadata == meta[si][cell],
                    "raw ID and metadata reach resolver unchanged");
                for (int i = 0; i < RAW.length; i++) if (RAW[i] == id) rawMask |= 1 << i;
                return overrideLook == Integer.MIN_VALUE ? resolve(id, metadata, x, y, z) : overrideLook;
            }

            @Override
            public ChunkSnapshot.Tints tints(int x, int z) {
                touch("tints");
                require(x == cx && z == cz, "tint chunk coordinates");
                return tint;
            }

            @Override
            public int fogColor(int x, int z) {
                touch("fog");
                require(x == cx && z == cz, "fog chunk coordinates");
                return 0xab456789;
            }
        };

        @Override
        public int chunkX() {
            touch("chunkX");
            return actualX;
        }

        @Override
        public int chunkZ() {
            touch("chunkZ");
            return actualZ;
        }

        @Override
        public int minSectionY() {
            touch("minSection");
            return minSection;
        }

        @Override
        public int sectionCount() {
            touch("sectionCount");
            return reportedCount;
        }

        @Override
        public boolean hasSkyLight() {
            touch("hasSky");
            return sky;
        }

        @Override
        public ChunkCapture.Section section(int si) {
            touch("section");
            if (nullSections[si]) return null;
            return new ChunkCapture.Section() {

                @Override
                public int blockId(int cell) {
                    touch("blockId");
                    return raw[si][cell];
                }

                @Override
                public int metadata(int cell) {
                    touch("metadata");
                    return meta[si][cell];
                }
            };
        }

        @Override
        public int blockLight(int si, int cell) {
            touch("blockLight");
            return blocks[si][cell];
        }

        @Override
        public int skyLight(int si, int cell) {
            touch("skyLight");
            return skies[si][cell];
        }
    }

    private static void status(ChunkCapture.Result r, ChunkCapture.Status status, Object epoch) {
        require(r.status() == status, "explicit capture status " + status);
        require((r.snapshot() != null) == (status == ChunkCapture.Status.READY), "only READY exposes snapshot");
        require(r.session() == epoch, "opaque result epoch identity");
        put(status.ordinal());
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation, String message) {
        try {
            operation.run();
        } catch (Throwable e) {
            require(type.isInstance(e), message + ": unexpected " + e);
            return;
        }
        throw new AssertionError(message + ": did not throw " + type.getName());
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void put(int v) {
        HASH.update((byte) (v >>> 24));
        HASH.update((byte) (v >>> 16));
        HASH.update((byte) (v >>> 8));
        HASH.update((byte) v);
    }
}
