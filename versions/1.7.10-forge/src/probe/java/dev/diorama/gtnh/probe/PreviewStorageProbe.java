package dev.diorama.gtnh.probe;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import dev.diorama.Projection;
import dev.diorama.bake.BlockLight;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.MapDimensionIdentity;
import dev.diorama.gtnh.capture.PreviewOverview;
import dev.diorama.gtnh.capture.PreviewTileStore;
import dev.diorama.gtnh.capture.RemoteWorldIdentity;

/** Offline fixtures for the bounded preview's canonical pixel kernel and isolated storage subset. */
public final class PreviewStorageProbe {

    private static final int EDGE = Projection.TILE_PIXELS;
    private static final int PIXELS = EDGE * EDGE;
    private static final int COLOR = 0xFF563412;
    private static final TileKey CHILD = new TileKey(TileKey.SURFACE, -1, -1);
    private static final TileKey PARENT = CHILD.parent();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        int[] overview = overview();
        Path temp = Files.createTempDirectory("diorama-preview-probe-");
        int[] merged;
        try {
            merged = persistence(temp, overview);
            saveSession(temp, merged);
            dimensionStores(temp, merged);
            remoteStores(temp, merged);
        } finally {
            Path[] paths;
            try (Stream<Path> tree = Files.walk(temp)) {
                paths = tree.sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new);
            }
            for (Path path : paths) Files.delete(path);
        }
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        ByteBuffer pixel = ByteBuffer.allocate(Integer.BYTES);
        for (int[] image : new int[][] { overview, merged }) {
            for (int value : image) hash.update(
                pixel.putInt(0, value)
                    .array());
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS preview storage: " + assertions
                + " assertions; LOD1, OMT1 subset, validation, retention, dimension roots and shared save lock");
    }

    private static void saveSession(Path temp, int[] pixels) throws Exception {
        Path save = Files.createDirectory(temp.resolve("singleplayer"));
        Path lock = save.resolve("session.lock");
        writeSessionLock(lock, 21L);
        PreviewTileStore stale = PreviewTileStore.forSave(save);
        Files.delete(lock);
        Files.delete(save);
        expect(IOException.class, () -> stale.write(CHILD, pixels));
        require(!Files.exists(save), "deleted save remains deleted");
        Files.createDirectory(save);
        writeSessionLock(lock, 22L);
        expect(IOException.class, () -> stale.write(CHILD, pixels));
        PreviewTileStore current = PreviewTileStore.forSave(save);
        require(current.read(CHILD) == null, "replacement save has no inherited terrain");
        current.write(CHILD, pixels);
        writeSessionLock(lock, 23L);
        expect(IOException.class, () -> current.read(CHILD));
        require(
            Arrays.equals(
                pixels,
                PreviewTileStore.forSave(save)
                    .read(CHILD)),
            "the same save restores terrain after legitimate reopen");
    }

    private static void dimensionStores(Path temp, int[] pixels) throws Exception {
        Path save = Files.createDirectory(temp.resolve("dimension-save"));
        Path lock = save.resolve("session.lock");
        writeSessionLock(lock, 31L);
        expect(IllegalArgumentException.class, () -> PreviewTileStore.forSave(save, "../other"));
        expect(IllegalArgumentException.class, () -> PreviewTileStore.forSave(save, null));
        String[] suffixes = { "overworld", "nether", "end" };
        PreviewTileStore[] stores = new PreviewTileStore[suffixes.length];
        int[][] rasters = { pixels, pixels.clone(), pixels.clone() };
        rasters[1][0] = 0xFF123456;
        rasters[2][0] = 0xFF654321;
        for (int i = 0; i < stores.length; i++) {
            stores[i] = i == 0 ? PreviewTileStore.forSave(save) : PreviewTileStore.forSave(save, suffixes[i]);
            require(stores[i].read(CHILD) == null, "different dimension does not see previous writes");
            stores[i].write(CHILD, rasters[i]);
            require(
                Files.isRegularFile(
                    save.resolve("diorama-terrain-preview-v1-" + suffixes[i])
                        .resolve("-1_-1.omt")),
                "dimension tile has isolated root: " + suffixes[i]);
        }
        writeSessionLock(lock, 32L);
        for (int i = 0; i < stores.length; i++) {
            PreviewTileStore stale = stores[i];
            expect(IOException.class, () -> stale.read(CHILD));
            expect(IOException.class, () -> stale.write(CHILD, pixels));
            require(
                Arrays.equals(
                    rasters[i],
                    PreviewTileStore.forSave(save, suffixes[i])
                        .read(CHILD)),
                "reopened dimension root preserves its own raster: " + suffixes[i]);
        }
    }

    private static void remoteStores(Path temp, int[] pixels) throws Exception {
        require(RemoteWorldIdentity.profile(temp,"a")==null,"no default remote profile");
        RemoteWorldIdentity.setProfile(temp,"a","alpha world");
        RemoteWorldIdentity.setProfile(temp,"b","beta");
        require("alpha world".equals(RemoteWorldIdentity.profile(temp,"a"))&&"beta".equals(RemoteWorldIdentity.profile(temp,"b")),
            "profiles persist and remain endpoint-scoped");
        RemoteWorldIdentity.setProfile(temp,"a","second");
        require("second".equals(RemoteWorldIdentity.profile(temp,"a"))&&"beta".equals(RemoteWorldIdentity.profile(temp,"b")),
            "changing one server preserves another");
        RemoteWorldIdentity.setProfile(temp,"a",null);
        require(RemoteWorldIdentity.profile(temp,"a")==null,"disabling a profile leaves it session-only");
        expect(IllegalArgumentException.class,()->RemoteWorldIdentity.setProfile(temp,"a","bad\nlabel"));
        require(RemoteWorldIdentity.open(temp, "a", null, "world") == null, "opt-in required");
        require(RemoteWorldIdentity.open(temp, "a", "b", "world") == null, "endpoint must match");
        require(RemoteWorldIdentity.open(temp, "a", "a", "") == null, "explicit world required");
        require(RemoteWorldIdentity.open(temp, "a", "a", "bad\nlabel") == null, "control characters rejected");
        Path first = RemoteWorldIdentity.open(temp, "a", "a", "world");
        Path again = RemoteWorldIdentity.open(temp, "a", "a", "world");
        Path second = RemoteWorldIdentity.open(temp, "a", "a", "world-2");
        Path otherServer = RemoteWorldIdentity.open(temp, "b", "b", "world");
        require(first.equals(again) && !first.equals(second) && !first.equals(otherServer), "namespace isolation");
        PreviewTileStore original = PreviewTileStore.forSave(first, "dim-7-surface");
        original.write(CHILD, pixels);
        require(
            Arrays.equals(
                pixels,
                PreviewTileStore.forSave(again, "dim-7-surface")
                    .read(CHILD)),
            "remote reconnect restores tile");
        require(
            PreviewTileStore.forSave(second, "dim-7-surface")
                .read(CHILD) == null,
            "new world isolated");
        require(
            PreviewTileStore.forSave(first, "dim-8-surface")
                .read(CHILD) == null,
            "dimension isolated");
        require(
            PreviewTileStore.forSave(first, "dim-7-cave")
                .read(CHILD) == null,
            "projection isolated");
        require(
            PreviewTileStore.forSave(otherServer, "dim-7-surface")
                .read(CHILD) == null,
            "server isolated");
        long previous;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(first.resolve("session.lock")))) {
            previous = in.readLong();
        }
        writeSessionLock(first.resolve("session.lock"), previous ^ 1L);
        expect(IOException.class, () -> original.write(CHILD, pixels));
        require(
            MapDimensionIdentity.supportsHeight(256) && !MapDimensionIdentity.supportsHeight(128),
            "only audited height accepted");
        require(
            MapDimensionIdentity.surface(7, false, false) && !MapDimensionIdentity.surface(7, true, false),
            "modded dimensions use sky policy");
        require(MapDimensionIdentity.surface(1, true, true), "End remains surface");
        for (int id : new int[] { Integer.MIN_VALUE, -7, 0, 1, 7, Integer.MAX_VALUE }) {
            require(
                MapDimensionIdentity.validSuffix(MapDimensionIdentity.suffix(id, false, false)),
                "signed dimension id roundtrips");
        }
        for (String bad : new String[] { "dim-07-surface", "dim-2147483648-cave", "../other", "dim--0-cave" })
            require(!MapDimensionIdentity.validSuffix(bad), "noncanonical path rejected");
    }

    private static void writeSessionLock(Path path, long id) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeLong(id);
        }
    }

    private static int[] overview() throws Exception {
        int[] sparse = new int[PIXELS];
        sparse[0] = COLOR;
        sparse[1] = 0x00999999;
        int[] green = new int[PIXELS];
        int[] blue = new int[PIXELS];
        Arrays.fill(green, 0xFF008800);
        Arrays.fill(blue, 0xFFCC0000);
        int[] overview = PreviewOverview.build(PARENT, new int[][] { green, blue, null, sparse });
        require(overview.length == PIXELS, "LOD1 uses the original edge");
        require(overview[0] == green[0], "north-west child");
        require(overview[EDGE / 2] == blue[0], "north-east child");
        require(overview[(EDGE / 2) * EDGE] == 0, "missing south-west child stays unknown");
        require(overview[(EDGE / 2) * (EDGE + 1)] == 0x3F563412, "known alpha-weighted over unknown");
        require(overview[PIXELS - 1] == 0, "empty child tail stays unknown");
        int[] filtered = Mipmaps.build(sparse, EDGE)[1];
        boolean agrees = true;
        for (int y = 0; y < EDGE / 2; y++) for (int x = 0; x < EDGE / 2; x++)
            agrees &= overview[(y + EDGE / 2) * EDGE + x + EDGE / 2] == filtered[y * (EDGE / 2) + x];
        require(agrees, "child contents match the canonical first mip exactly");
        require(PARENT.tileU() == -1 && PARENT.tileV() == -1 && PARENT.lod() == 1, "negative floor-div parent");
        require(
            PreviewOverview.childKey(PARENT, 0)
                .equals(new TileKey(TileKey.SURFACE, -2, -2)),
            "negative origin");
        for (int i = 0; i < 4; i++) {
            TileKey child = PreviewOverview.childKey(PARENT, i);
            require(
                child.parent()
                    .equals(PARENT) && PreviewOverview.childIndex(child) == i,
                "child roundtrip " + i);
        }
        require(
            PreviewOverview.childKey(PARENT, 3)
                .equals(CHILD),
            "negative parity child");
        expect(IllegalArgumentException.class, () -> PreviewOverview.childIndex(PARENT));
        expect(IllegalArgumentException.class, () -> PreviewOverview.childKey(PARENT, 4));
        expect(
            IllegalArgumentException.class,
            () -> PreviewOverview.childKey(new TileKey(TileKey.SURFACE, Integer.MAX_VALUE, 0, 1), 0));
        expect(IllegalArgumentException.class, () -> PreviewOverview.build(CHILD, new int[4][]));
        expect(IllegalArgumentException.class, () -> PreviewOverview.build(PARENT, new int[3][]));
        expect(
            IllegalArgumentException.class,
            () -> PreviewOverview.build(PARENT, new int[][] { null, null, new int[1], null }));
        return overview;
    }

    private static int[] persistence(Path temp, int[] overview) throws Exception {
        Path root = temp.resolve("save-a")
            .resolve("dim-0")
            .resolve("profile-1");
        PreviewTileStore store = new PreviewTileStore(root);
        Path file = root.resolve("-1_-1.omt");
        int[] blank = new int[PIXELS];
        blank[0] = 0x00112233;
        require(store.read(CHILD) == null, "no saved tile is a miss");
        store.write(CHILD, blank);
        require(!Files.exists(root), "all-unknown raster never creates a directory");
        require(PreviewTileStore.retain(blank, null) == null, "neither raster has known pixels");

        int[] stored = new int[PIXELS];
        stored[0] = COLOR;
        stored[1] = 0xFF010203;
        stored[PIXELS - 1] = 0xA0776633;
        store.write(CHILD, stored);
        try (DataInputStream raw = new DataInputStream(Files.newInputStream(file))) {
            require(raw.readInt() == 0x4F4D5431 && raw.readInt() == EDGE, "canonical big-endian OMT1 header");
            try (DataInputStream data = new DataInputStream(new InflaterInputStream(raw))) {
                require(data.readInt() == Integer.reverseBytes(COLOR), "canonical little-endian ABGR body");
            }
        }
        int[] restored = new PreviewTileStore(root).read(CHILD);
        require(Arrays.equals(restored, stored), "independent store reopens exact raster");
        require(store.read(PARENT) == null, "same coords at LOD1 do not read LOD0");
        require(
            new PreviewTileStore(
                temp.resolve("save-b")
                    .resolve("dim-0")
                    .resolve("profile-1")).read(CHILD)
                == null,
            "different save root");
        require(
            new PreviewTileStore(
                root.getParent()
                    .getParent()
                    .resolve("dim-1")
                    .resolve("profile-1")).read(CHILD)
                == null,
            "different dimension root");
        require(new PreviewTileStore(root.resolveSibling("profile-2")).read(CHILD) == null, "different profile root");

        int[] fresh = new int[PIXELS];
        fresh[0] = 0x00776655;
        fresh[1] = 0x7F123456;
        fresh[2] = 0xFF000000;
        int[] merged = PreviewTileStore.retain(restored, fresh);
        require(merged != fresh && merged != restored, "partial overlay does not mutate inputs");
        require(fresh[0] == 0x00776655 && restored[1] == stored[1], "inputs unchanged");
        require(merged[0] == COLOR && merged[PIXELS - 1] == stored[PIXELS - 1], "unknown fresh retains history");
        require(merged[1] == fresh[1] && merged[2] == fresh[2], "known fresh wins even with partial alpha or black");
        require(PreviewTileStore.retain(restored, blank) == restored, "all-unknown refresh reuses history");
        require(PreviewTileStore.retain(null, fresh) == fresh, "no history reuses fresh coverage");
        store.write(CHILD, merged);
        store.write(CHILD, null);
        require(Arrays.equals(merged, store.read(CHILD)), "all-unknown write does not wipe tile");
        store.write(PARENT, overview);
        require(
            Files.isRegularFile(
                root.resolve("lod1")
                    .resolve("-1_-1.omt")),
            "canonical LOD1 subfolder");
        require(Arrays.equals(overview, new PreviewTileStore(root).read(PARENT)), "overview reopens independently");
        require(Arrays.equals(merged, store.read(CHILD)), "overview write did not change child");

        expect(IllegalArgumentException.class, () -> store.write(CHILD, new int[1]));
        expect(IllegalArgumentException.class, () -> PreviewTileStore.retain(new int[1], fresh));
        expect(IllegalArgumentException.class, () -> store.read(new TileKey(5, 0, 0)));
        expect(IllegalArgumentException.class, () -> store.read(new TileKey(TileKey.SURFACE, 0, 0, 2)));
        byte[] good = Files.readAllBytes(file);
        byte[] badMagic = good.clone();
        badMagic[0] ^= 1;
        byte[] badEdge = good.clone();
        badEdge[7] ^= 1;
        byte[] badChecksum = good.clone();
        badChecksum[badChecksum.length - 1] ^= 1;
        byte[] truncated = Arrays.copyOf(good, good.length - 2);
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        reject(store, file, badMagic);
        reject(store, file, badEdge);
        reject(store, file, badChecksum);
        reject(store, file, truncated);
        reject(store, file, trailing);
        reject(store, file, encodedZeros(1, false));
        reject(store, file, encodedZeros(PIXELS * Integer.BYTES, true));
        reject(store, file, new byte[PIXELS * Integer.BYTES + (1 << 17)]);
        return merged;
    }

    private static byte[] encodedZeros(int bytes, boolean modernTail) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream header = new DataOutputStream(raw)) {
            header.writeInt(0x4F4D5431);
            header.writeInt(EDGE);
            try (DataOutputStream data = new DataOutputStream(new DeflaterOutputStream(raw))) {
                data.write(new byte[bytes]);
                if (modernTail) {
                    data.writeInt(Integer.reverseBytes(0x4C495431)); // Canonical LIT1 sidecar
                    data.write(new byte[BlockLight.EDGE * BlockLight.EDGE]);
                }
            }
        }
        return raw.toByteArray();
    }

    private static void reject(PreviewTileStore store, Path file, byte[] damaged) throws Exception {
        Files.write(file, damaged);
        expect(IOException.class, () -> store.read(CHILD));
        int[] replacement = new int[PIXELS];
        replacement[0] = COLOR;
        expect(IOException.class, () -> store.write(CHILD, replacement));
        require(Arrays.equals(damaged, Files.readAllBytes(file)), "unsupported file not replaced");
    }

    @FunctionalInterface
    private interface Checked {

        void run() throws Exception;
    }

    private static void expect(Class<? extends Exception> type, Checked checked) throws Exception {
        try {
            checked.run();
        } catch (Exception failure) {
            if (!type.isInstance(failure)) throw failure;
            assertions++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }
}
