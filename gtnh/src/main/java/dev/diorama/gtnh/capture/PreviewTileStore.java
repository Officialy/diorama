package dev.diorama.gtnh.capture;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import org.jspecify.annotations.Nullable;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;

/**
 * Detached, worker-only pixel storage for the bounded GTNH preview. The owner supplies a dedicated
 * save/dimension/profile directory and serializes its writes. Never point this at a modern store.
 * Uses the pixel-only subset of canonical OMT1: big-endian magic and edge, then zlib-compressed
 * little-endian ABGR ints. Modern sidecars, scheduling, and automatic overview rebuilds are not
 * part of this store. Unsupported or damaged files are left in place, not treated as misses.
 */
public final class PreviewTileStore {

    private static final int MAGIC = 0x4F4D5431;
    private static final int EDGE = Projection.TILE_PIXELS;
    private static final int PIXELS = EDGE * EDGE;
    private static final int BUFFER = 1 << 16;
    // More than zlib needs for an incompressible tile; bounds work even for streams of empty blocks.
    private static final long MAX_FILE_BYTES = (long) PIXELS * Integer.BYTES + BUFFER;

    private final Path root;
    private final @Nullable Path sessionLock;
    private final long sessionId;

    /** Unbound directory for detached fixtures or callers with their own lifetime guard. */
    public PreviewTileStore(Path identityRoot) {
        this(
            Objects.requireNonNull(identityRoot, "identityRoot")
                .toAbsolutePath()
                .normalize(),
            null,
            0);
    }

    private PreviewTileStore(Path root, @Nullable Path sessionLock, long sessionId) {
        this.root = root;
        this.sessionLock = sessionLock;
        this.sessionId = sessionId;
    }

    /** Open the original Overworld store. */
    public static PreviewTileStore forSave(Path directory) throws IOException {
        return forSave(directory, "overworld");
    }

    /** Bind the dimension-specific cache to vanilla's single lock at the integrated save root. */
    public static PreviewTileStore forSave(Path directory, String dimensionSuffix) throws IOException {
        if (!"overworld".equals(dimensionSuffix) && !"nether".equals(dimensionSuffix) && !"end".equals(dimensionSuffix))
            throw new IllegalArgumentException("Expected a vanilla map dimension suffix");
        Path save = directory.toAbsolutePath()
            .normalize();
        Path lock = save.resolve("session.lock");
        return new PreviewTileStore(
            save.resolve("diorama-terrain-preview-v1-" + dimensionSuffix),
            lock,
            readSession(lock));
    }

    private static long readSession(Path lock) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(lock))) {
            long id = in.readLong();
            if (in.read() != -1) throw new IOException("Invalid save session lock");
            return id;
        }
    }

    private void checkSession() throws IOException {
        if (this.sessionLock != null && readSession(this.sessionLock) != this.sessionId)
            throw new IOException("Save session changed");
    }

    /** Missing is null; corruption, a stale save, or unsupported contents are errors. */
    public int @Nullable [] read(TileKey key) throws IOException {
        this.checkSession();
        int[] pixels = read(this.file(key));
        this.checkSession();
        return pixels;
    }

    /**
     * Writes an accepted, already merged tile atomically. No coverage and identical content are
     * no-ops. An existing file is validated before replacement so unsupported data is not erased.
     */
    public void write(TileKey key, int @Nullable [] pixels) throws IOException {
        Path file = this.file(key);
        checkLength(pixels);
        if (!known(pixels)) return;
        int[] previous = this.read(key);
        if (Arrays.equals(previous, pixels)) return;

        if (this.sessionLock == null) Files.createDirectories(file.getParent());
        else {
            // Never create the save directory (or any of its ancestors) from a pending write.
            makeCacheDirectory(this.root);
            if (key.lod() == 1) makeCacheDirectory(file.getParent());
            this.checkSession();
        }
        Path temp = Files.createTempFile(file.getParent(), "preview-", ".tmp");
        try {
            try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                data.writeInt(MAGIC);
                data.writeInt(EDGE);
                try (DeflaterOutputStream zipped = new DeflaterOutputStream(data)) {
                    ByteBuffer buffer = buffer();
                    int stride = BUFFER / Integer.BYTES;
                    for (int offset = 0; offset < PIXELS; offset += stride) {
                        int count = Math.min(stride, PIXELS - offset);
                        buffer.asIntBuffer()
                            .put(pixels, offset, count);
                        zipped.write(buffer.array(), 0, count * Integer.BYTES);
                    }
                }
            }
            // Check with the temp file already in place: if a save is removed after this check,
            // its uniquely named source also disappears and cannot be published to the replacement.
            this.checkSession();
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    /**
     * Fresh nonzero-alpha pixels replace history; a fresh unknown never erases known history.
     * Returns null if neither input has coverage. The returned array may be one of the inputs;
     * callers must keep both inputs and the result read-only.
     */
    public static int @Nullable [] retain(int @Nullable [] history, int @Nullable [] fresh) {
        checkLength(history);
        checkLength(fresh);
        if (!known(fresh)) return known(history) ? history : null;
        if (history == null) return fresh;
        int[] merged = fresh;
        for (int i = 0; i < PIXELS; i++) {
            if ((fresh[i] >>> 24) == 0 && (history[i] >>> 24) != 0) {
                if (merged == fresh) merged = fresh.clone();
                merged[i] = history[i];
            }
        }
        return merged;
    }

    private static void makeCacheDirectory(Path dir) throws IOException {
        try {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException exists) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw exists;
        }
    }

    private Path file(TileKey key) {
        if (key.layer() != TileKey.SURFACE || (key.lod() != 0 && key.lod() != 1))
            throw new IllegalArgumentException("Expected surface LOD0 or LOD1 tile");
        Path folder = key.lod() == 0 ? this.root : this.root.resolve("lod1");
        return folder.resolve(key.tileU() + "_" + key.tileV() + ".omt");
    }

    private static int @Nullable [] read(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return null;
        }
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES)
            throw new IOException("Invalid preview tile file: " + file);
        try (DataInputStream data = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (data.readInt() != MAGIC || data.readInt() != EDGE)
                throw new IOException("Unsupported OMT1 preview header: " + file);
            try (PixelStream zipped = new PixelStream(data); DataInputStream raster = new DataInputStream(zipped)) {
                ByteBuffer buffer = buffer();
                int[] pixels = new int[PIXELS];
                int stride = BUFFER / Integer.BYTES;
                for (int offset = 0; offset < PIXELS; offset += stride) {
                    int count = Math.min(stride, PIXELS - offset);
                    raster.readFully(buffer.array(), 0, count * Integer.BYTES);
                    buffer.asIntBuffer()
                        .get(pixels, offset, count);
                }
                if (!zipped.exactEnd()) throw new IOException("Unsupported or damaged preview tile tail: " + file);
                return pixels;
            }
        }
    }

    private static ByteBuffer buffer() {
        return ByteBuffer.allocate(BUFFER)
            .order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void checkLength(int @Nullable [] pixels) {
        if (pixels != null && pixels.length != PIXELS)
            throw new IllegalArgumentException("Invalid preview raster length");
    }

    private static boolean known(int @Nullable [] pixels) {
        if (pixels != null) for (int pixel : pixels) if ((pixel >>> 24) != 0) return true;
        return false;
    }

    private static final class PixelStream extends InflaterInputStream {

        private PixelStream(InputStream in) {
            super(in);
        }

        private boolean exactEnd() throws IOException {
            // Trigger checksum verification without draining an arbitrary uncompressed tail.
            return this.read() == -1 && this.inf.finished() && this.inf.getRemaining() == 0 && this.in.read() == -1;
        }
    }
}
