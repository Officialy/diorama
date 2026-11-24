package dev.diorama.gtnh.probe;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import dev.diorama.bake.Looks;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.SectionBlocks;

/** Game-free packed-array fixtures and real capture orchestration; no native hook coverage. */
public final class SectionBlocksProbe {

    private static final MessageDigest HASH = digest();
    private static final int[] IDS = { 0, 1, 127, 128, 255, 256, 2048, 4095, 4096, 32768, 65535, 65536, 0x7fffff,
        0x800000, 0xff0000, 0xffffff };
    private static final int[] META = { 0, 1, 7, 8, 15, 16, 127, 128, 255, 256, 32768, 65535 };
    private static int assertions;

    public static void main(String[] args) {
        combinations();
        invalid();
        borrowed();
        capture();
        StringBuilder hex = new StringBuilder();
        for (byte b : HASH.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS section-blocks: " + assertions
                + " assertions; all cells, optional-layer combinations, packed oracle, random, guards, borrowed arrays, real ChunkCapture; native hooks/game UNTESTED");
    }

    private static void combinations() {
        int accepted = 0, rejected = 0;
        for (int mask = 0; mask < 32; mask++) {
            Packed p = new Packed(mask);
            if (!legal(mask)) {
                expect(IllegalArgumentException.class, p::view, "orphan layer mask " + mask);
                rejected++;
                continue;
            }
            accepted++;
            for (boolean random : new boolean[] { false, true }) {
                p.fill(random);
                byte[][] before = copy(p.layers);
                SectionBlocks view = p.view();
                verify(view, p);
                // The other traversal is also a permutation of every cell, not just a few coordinates.
                for (int i = 0; i < 4096; i++) {
                    int cell = (i * 2053 + 101) & 4095;
                    require(view.blockId(cell) == p.ids[cell], "permuted ID " + cell);
                    require(view.metadata(cell) == p.meta[cell], "permuted metadata " + cell);
                }
                require(Arrays.deepEquals(before, p.layers), "reading never changes borrowed arrays");
                if ((mask & ~1) == 0) {
                    SectionBlocks vanilla = SectionBlocks.vanilla(p.layers[0], p.layers[1], p.layers[4]);
                    verify(vanilla, p);
                    for (int cell = 0; cell < 4096; cell++) {
                        require(vanilla.blockId(cell) == view.blockId(cell), "vanilla ID parity");
                        require(vanilla.metadata(cell) == view.metadata(cell), "vanilla metadata parity");
                        require(vanilla.blockId(cell) <= 4095 && vanilla.metadata(cell) <= 15, "vanilla limits");
                    }
                }
            }
        }
        require(accepted == 12 && rejected == 20, "all 32 optional-layer combinations classified");
        put(accepted);
        put(rejected);
    }

    private static boolean legal(int mask) {
        return ((mask & 2) == 0 || (mask & 1) != 0) && ((mask & 4) == 0 || (mask & 2) != 0)
            && ((mask & 16) == 0 || (mask & 8) != 0);
    }

    private static void verify(SectionBlocks view, Packed p) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int cell = y * 256 + z * 16 + x;
            int id = view.blockId(cell), metadata = view.metadata(cell);
            require(id == p.ids[cell], "packed ID at " + cell);
            require(metadata == p.meta[cell], "packed metadata at " + cell);
            put(id);
            put(metadata);
        }
    }

    private static void invalid() {
        for (int layer = 0; layer < 7; layer++) {
            int size = layer == 0 || layer == 3 || layer == 6 ? 4096 : 2048;
            for (int badSize : new int[] { 0, size - 1, size + 1, size == 4096 ? 2048 : 4096 }) {
                Packed p = new Packed(31);
                p.layers[layer] = new byte[badSize];
                expect(IllegalArgumentException.class, p::view, "bad length layer " + layer);
            }
        }
        for (int layer : new int[] { 0, 4 }) {
            Packed p = new Packed(31);
            p.layers[layer] = null;
            expect(NullPointerException.class, p::view, "required EndlessIDs layer " + layer);
        }
        expect(NullPointerException.class, () -> SectionBlocks.vanilla(null, null, new byte[2048]), "vanilla low null");
        expect(
            NullPointerException.class,
            () -> SectionBlocks.vanilla(new byte[4096], null, null),
            "vanilla metadata null");
        for (int length : new int[] { 0, 2047, 2049, 4096 }) {
            expect(
                IllegalArgumentException.class,
                () -> SectionBlocks.vanilla(new byte[4096], new byte[length], new byte[2048]),
                "vanilla add length");
            expect(
                IllegalArgumentException.class,
                () -> SectionBlocks.vanilla(new byte[4096], null, new byte[length]),
                "vanilla metadata length");
        }
        for (int length : new int[] { 0, 4095, 4097, 2048 }) {
            expect(
                IllegalArgumentException.class,
                () -> SectionBlocks.vanilla(new byte[length], null, new byte[2048]),
                "vanilla low length");
        }
        for (SectionBlocks view : new SectionBlocks[] { new Packed(0).view(), new Packed(31).view(),
            SectionBlocks.vanilla(new byte[4096], null, new byte[2048]) }) {
            for (int cell : new int[] { Integer.MIN_VALUE, -4096, -1, 4096, 4097, Integer.MAX_VALUE }) {
                expect(IndexOutOfBoundsException.class, () -> view.blockId(cell), "ID bounds " + cell);
                expect(IndexOutOfBoundsException.class, () -> view.metadata(cell), "metadata bounds " + cell);
            }
            require(view.blockId(0) == 0 && view.metadata(4095) == 0, "valid bounds inclusive/exclusive");
        }
    }

    private static void borrowed() {
        Packed p = new Packed(31);
        SectionBlocks view = p.view();
        // Mutate every backing layer after construction. Optional zero layers must not be elided.
        int[] contribution = { 0x80, 0xf00, 0xf000, 0xff0000, 15, 0xf0, 0xff00 };
        for (int cell : new int[] { 0, 1, 4094, 4095 }) {
            int id = 0, metadata = 0;
            for (int layer = 0; layer < 7; layer++) {
                if (layer == 0 || layer == 3 || layer == 6) p.layers[layer][cell] = (byte) (layer == 0 ? 128 : 255);
                else writeNibble(p.layers[layer], cell, 15);
                if (layer < 4) id |= contribution[layer];
                else metadata |= contribution[layer];
                require(view.blockId(cell) == id, "borrowed ID layer " + layer);
                require(view.metadata(cell) == metadata, "borrowed metadata layer " + layer);
                put(id);
                put(metadata);
            }
        }
        Packed vanillaData = new Packed(1);
        SectionBlocks vanilla = SectionBlocks
            .vanilla(vanillaData.layers[0], vanillaData.layers[1], vanillaData.layers[4]);
        vanillaData.write(0, 4095, 15);
        vanillaData.write(1, 2048, 8);
        require(vanilla.blockId(0) == 4095 && vanilla.metadata(0) == 15, "vanilla borrows arrays");
        require(vanilla.blockId(1) == 2048 && vanilla.metadata(1) == 8, "vanilla adjacent nibble independent");
        vanillaData.write(0, 0, 0);
        require(vanilla.blockId(0) == 0 && vanilla.metadata(0) == 0, "borrowed values can clear");
        require(vanilla.blockId(1) == 2048 && vanilla.metadata(1) == 8, "clearing even nibble preserves odd");
    }

    /** Expected values are recorded while packing, never reconstructed by decoding the test bytes. */
    private static final class Packed {

        private final byte[][] layers = new byte[7][];
        private final int[] ids = new int[4096], meta = new int[4096];
        private final int idMask, metaMask;

        private Packed(int mask) {
            layers[0] = new byte[4096];
            layers[4] = new byte[2048];
            int[] optional = { 1, 2, 3, 5, 6 };
            for (int bit = 0; bit < optional.length; bit++) {
                int layer = optional[bit];
                if ((mask & (1 << bit)) != 0) layers[layer] = new byte[layer == 3 || layer == 6 ? 4096 : 2048];
            }
            idMask = 255 | ((mask & 1) == 0 ? 0 : 0xf00)
                | ((mask & 2) == 0 ? 0 : 0xf000)
                | ((mask & 4) == 0 ? 0 : 0xff0000);
            metaMask = 15 | ((mask & 8) == 0 ? 0 : 0xf0) | ((mask & 16) == 0 ? 0 : 0xff00);
        }

        private SectionBlocks view() {
            return SectionBlocks.endless(layers[0], layers[1], layers[2], layers[3], layers[4], layers[5], layers[6]);
        }

        private void fill(boolean random) {
            Random rng = new Random(0x5ec710L);
            for (int i = 0; i < 4096; i++) {
                int cell = (i * 4051 + 37) & 4095;
                int id = random ? rng.nextInt(1 << 24) : IDS[i % IDS.length];
                int metadata = random ? rng.nextInt(1 << 16) : META[(i / IDS.length + i) % META.length];
                write(cell, id & idMask, metadata & metaMask);
            }
            // Explicit extrema at both nibble parities and one set bit at every ID/metadata position.
            write(0, idMask, metaMask);
            write(1, idMask, metaMask);
            for (int bit = 0; bit < 24; bit++) write(2 + bit, (1 << bit) & idMask, 0);
            for (int bit = 0; bit < 16; bit++) write(32 + bit, 0, (1 << bit) & metaMask);
        }

        private void write(int cell, int id, int metadata) {
            ids[cell] = id;
            meta[cell] = metadata;
            layers[0][cell] = (byte) id;
            if (layers[1] != null) writeNibble(layers[1], cell, (id / 256) % 16);
            if (layers[2] != null) writeNibble(layers[2], cell, (id / 4096) % 16);
            if (layers[3] != null) layers[3][cell] = (byte) (id / 65536);
            writeNibble(layers[4], cell, metadata % 16);
            if (layers[5] != null) writeNibble(layers[5], cell, (metadata / 16) % 16);
            if (layers[6] != null) layers[6][cell] = (byte) (metadata / 256);
        }
    }

    private static void writeNibble(byte[] data, int cell, int value) {
        int pair = data[cell / 2] & 255;
        // Arithmetic packing deliberately differs from the production shift/mask decoder.
        data[cell / 2] = (byte) (cell % 2 == 0 ? (pair / 16) * 16 + value : pair % 16 + value * 16);
    }

    private static void capture() {
        Packed p = new Packed(31);
        p.fill(true);
        Integration f = new Integration(p);
        ChunkCapture.Result result = ChunkCapture.capture(f, f, -3, 5);
        require(result.status() == ChunkCapture.Status.READY, "real capture ready");
        require(result.session() == f.epoch && result.snapshot() != null, "capture epoch and payload");
        require(f.calls == 4096 && f.borrows == 1, "capture resolves every packed cell from one borrow");
        ChunkSnapshot snapshot = result.snapshot();
        require(snapshot.chunkX == -3 && snapshot.chunkZ == 5 && snapshot.minSectionY == -2, "capture coordinates");
        int[] captured = snapshot.sectionIds(0);
        require(captured != null && captured.length == 4096, "capture keeps varying prepared IDs");
        for (byte[] layer : p.layers) Arrays.fill(layer, (byte) 0);
        for (int cell = 0; cell < 4096; cell++) {
            require(f.section.blockId(cell) == 0 && f.section.metadata(cell) == 0, "borrow observes later mutation");
            require(captured[cell] == 1 + (cell & 1), "captured prepared IDs detached from borrowed bytes");
            put(captured[cell]);
        }
    }

    private static final class TestLooks extends Looks {

        private TestLooks() {
            super(3, 3);
            publish(0, Looks.AIR);
            publish(1, Looks.SOLID);
            publish(2, Looks.SOLID);
        }

        @Override
        protected void make(int id) {
            throw new AssertionError("capture cannot prepare appearance");
        }
    }

    private static final class Integration implements ChunkCapture.Access, ChunkCapture.Chunk, ChunkCapture.Inputs {

        private final Object epoch = new Object();
        private final TestLooks looks = new TestLooks();
        private final Packed packed;
        private final SectionBlocks section;
        private final ChunkSnapshot.Tints tint;
        private int calls, borrows;

        private Integration(Packed packed) {
            this.packed = packed;
            this.section = packed.view();
            int[] white = new int[256];
            Arrays.fill(white, 0xffffff);
            tint = new ChunkSnapshot.Tints(white, white, white, white);
        }

        @Override
        public boolean onThread() {
            return true;
        }

        @Override
        public boolean current() {
            return true;
        }

        @Override
        public Object session() {
            return epoch;
        }

        @Override
        public ChunkCapture.Chunk loaded(int x, int z) {
            require(x == -3 && z == 5, "loaded-only capture request");
            return this;
        }

        @Override
        public int chunkX() {
            return -3;
        }

        @Override
        public int chunkZ() {
            return 5;
        }

        @Override
        public int minSectionY() {
            return -2;
        }

        @Override
        public int sectionCount() {
            return 1;
        }

        @Override
        public boolean hasSkyLight() {
            return false;
        }

        @Override
        public ChunkCapture.Section section(int index) {
            require(index == 0, "section index");
            borrows++;
            return section;
        }

        @Override
        public int blockLight(int index, int cell) {
            return 0;
        }

        @Override
        public int skyLight(int index, int cell) {
            throw new AssertionError("no skylight");
        }

        @Override
        public Looks looks() {
            return looks;
        }

        @Override
        public int airId() {
            return 0;
        }

        @Override
        public int lookId(int id, int metadata, int x, int y, int z, int aboveRawId) {
            int cell = calls++;
            require(
                x == -48 + cell % 16 && y == -32 + cell / 256 && z == 80 + (cell / 16) % 16,
                "capture cell order and world coordinates");
            require(id == packed.ids[cell] && metadata == packed.meta[cell], "packed raw values reach real resolver");
            return 1 + (cell & 1);
        }

        @Override
        public ChunkSnapshot.Tints tints(int x, int z, int[] surface) {
            return tint;
        }

        @Override
        public int fogColor(int x, int z) {
            return 0x123456;
        }
    }

    private static byte[][] copy(byte[][] layers) {
        byte[][] copy = new byte[layers.length][];
        for (int i = 0; i < layers.length; i++) copy[i] = layers[i] == null ? null : layers[i].clone();
        return copy;
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            require(type.isInstance(failure), message + ": unexpected " + failure);
            return;
        }
        throw new AssertionError(message + ": no exception");
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void put(int value) {
        HASH.update((byte) (value >>> 24));
        HASH.update((byte) (value >>> 16));
        HASH.update((byte) (value >>> 8));
        HASH.update((byte) value);
    }
}
