package dev.diorama.gtnh.capture;

import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.Looks;
import dev.diorama.data.ChunkSnapshot;

/** Copies a borrowed client-thread chunk into prepared CPU inputs, without retaining world views. */
public final class ChunkCapture {

    private ChunkCapture() {}

    public interface Access {

        boolean onThread();

        boolean current();

        /** Opaque epoch identity, never the live world itself. */
        Object session();

        /** Borrows an already loaded chunk; this must never load, generate or request one. */
        @Nullable
        Chunk loaded(int chunkX, int chunkZ);
    }

    public interface Chunk {

        int chunkX();

        int chunkZ();

        int minSectionY();

        int sectionCount();

        boolean hasSkyLight();

        /** Null means known air; lighting still comes from the chunk-level accessors below. */
        @Nullable
        Section section(int index);

        int blockLight(int sectionIndex, int cell);

        int skyLight(int sectionIndex, int cell);
    }

    public interface Section {

        /** Cell order is (localY << 8) | (localZ << 4) | localX. */
        int blockId(int cell);

        int metadata(int cell);
    }

    /**
     * Already prepared, session-bound appearance. IDs may include contextual variants, so raw block
     * ID and metadata alone are not presumed to identify a renderable look. Callbacks run only on
     * the source thread; no worker is asked to query blocks, models, tint code or textures.
     */
    public interface Inputs {

        Object session();

        Looks looks();

        int airId();

        /** A published valid look ID, or negative if appearance is not ready/supported. */
        int lookId(int blockId, int metadata, int worldX, int worldY, int worldZ);

        /** Null means not ready; white arrays are not an implicit success fallback. */
        ChunkSnapshot.@Nullable Tints tints(int chunkX, int chunkZ);

        int fogColor(int chunkX, int chunkZ);
    }

    public enum Status {
        READY,
        MISSING_CHUNK,
        PENDING_APPEARANCE,
        STALE_SESSION
    }

    /** Only READY carries a snapshot. The opaque token permits a later acceptance-time epoch check. */
    public record Result(Status status, @Nullable ChunkSnapshot snapshot, @Nullable Object session) {

        public Result {
            Objects.requireNonNull(status, "status");
            if ((status == Status.READY) != (snapshot != null))
                throw new IllegalArgumentException("Only READY may carry a snapshot");
            if (snapshot != null && session == null)
                throw new IllegalArgumentException("A ready snapshot requires its capture session");
        }
    }

    /**
     * Captures one complete vertical span. Invalid payloads throw; unavailable data has an explicit
     * status. No borrowed chunk/section, callback or access object escapes into the result.
     */
    public static Result capture(Access access, Inputs inputs, int chunkX, int chunkZ) {
        Objects.requireNonNull(access, "access");
        // This must precede even current(), session(), and every Inputs callback.
        if (!access.onThread()) throw new IllegalStateException("Chunk capture requires the source thread");
        Objects.requireNonNull(inputs, "inputs");
        if (!access.current()) return result(Status.STALE_SESSION, null);
        Object session = Objects.requireNonNull(access.session(), "capture session");
        try {
            current(access, session);
            if (inputs.session() != session) return result(Status.STALE_SESSION, session);
            current(access, session);
            Chunk chunk = access.loaded(chunkX, chunkZ);
            current(access, session);
            if (chunk == null) {
                if (inputs.session() != session) throw new Stale();
                current(access, session);
                return result(Status.MISSING_CHUNK, session);
            }

            int actualX = chunk.chunkX();
            current(access, session);
            int actualZ = chunk.chunkZ();
            current(access, session);
            if (actualX != chunkX || actualZ != chunkZ)
                throw new IllegalArgumentException("Borrowed chunk coordinates do not match request");
            int minSectionY = chunk.minSectionY();
            current(access, session);
            int count = chunk.sectionCount();
            current(access, session);
            if (count <= 0 || (long) minSectionY * 16 < Integer.MIN_VALUE
                || ((long) minSectionY + count) * 16 - 1 > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid captured vertical span");
            int worldX0 = origin(chunkX);
            int worldZ0 = origin(chunkZ);
            boolean hasSkyLight = chunk.hasSkyLight();
            current(access, session);
            Looks looks = Objects.requireNonNull(inputs.looks(), "looks");
            current(access, session);
            int airId = inputs.airId();
            current(access, session);
            if (kind(looks, airId) != Looks.AIR)
                throw new IllegalArgumentException("Prepared air ID does not identify air");
            current(access, session);

            int[][] sections = new int[count][];
            byte[][] blockLight = new byte[count][];
            byte[][] skyLight = new byte[count][];
            int[] surface = new int[256];
            Arrays.fill(surface, Integer.MIN_VALUE);
            int maxY = Integer.MIN_VALUE;
            for (int sectionIndex = 0; sectionIndex < count; sectionIndex++) {
                current(access, session);
                Section section = chunk.section(sectionIndex);
                current(access, session);
                int[] ids = section == null ? null : new int[4096];
                byte[] blocks = new byte[4096];
                byte[] sky = new byte[4096];
                int first = -1;
                boolean uniform = true;
                for (int cell = 0; cell < 4096; cell++) {
                    current(access, session);
                    int y = (minSectionY + sectionIndex) * 16 + (cell >>> 8);
                    if (section != null) {
                        int blockId = section.blockId(cell);
                        current(access, session);
                        int metadata = section.metadata(cell);
                        current(access, session);
                        if (blockId < 0 || metadata < 0 || metadata > 15)
                            throw new IllegalArgumentException("Invalid captured block ID or metadata");
                        int id = inputs
                            .lookId(blockId, metadata, worldX0 + (cell & 15), y, worldZ0 + ((cell >>> 4) & 15));
                        current(access, session);
                        if (id < 0) throw new Pending();
                        byte material = kind(looks, id);
                        current(access, session);
                        ids[cell] = id;
                        if (cell == 0) first = id;
                        else uniform &= id == first;
                        if (material != Looks.AIR) {
                            int column = cell & 255;
                            surface[column] = Math.max(surface[column], y);
                            maxY = Math.max(maxY, y);
                        }
                    }
                    int blockValue = chunk.blockLight(sectionIndex, cell);
                    current(access, session);
                    blocks[cell] = light(blockValue);
                    if (hasSkyLight) {
                        int skyValue = chunk.skyLight(sectionIndex, cell);
                        current(access, session);
                        sky[cell] = light(skyValue);
                    }
                }
                sections[sectionIndex] = ids == null ? null : uniform ? new int[] { first } : ids;
                // Even known-air sections retain their captured 1.7.10 height-map light values.
                blockLight[sectionIndex] = blocks;
                skyLight[sectionIndex] = sky;
            }
            current(access, session);
            ChunkSnapshot.Tints tints = inputs.tints(chunkX, chunkZ);
            current(access, session);
            if (tints == null) throw new Pending();
            int fog = inputs.fogColor(chunkX, chunkZ);
            current(access, session);
            if (inputs.session() != session) throw new Stale();
            current(access, session);
            if (inputs.looks() != looks) throw new Stale();
            current(access, session);
            if (inputs.airId() != airId) throw new Stale();
            current(access, session);
            ChunkSnapshot snapshot = new ChunkSnapshot(
                chunkX,
                chunkZ,
                minSectionY,
                airId,
                looks,
                sections,
                blockLight,
                skyLight,
                hasSkyLight,
                surface,
                maxY,
                fog,
                tints);
            current(access, session);
            return new Result(Status.READY, snapshot, session);
        } catch (Pending | Looks.StatesRenumbered pending) {
            // Session changes win over an appearance miss raised by the same callback.
            if (!access.current() || access.session() != session || inputs.session() != session)
                return result(Status.STALE_SESSION, session);
            if (!access.current() || access.session() != session) return result(Status.STALE_SESSION, session);
            return result(Status.PENDING_APPEARANCE, session);
        } catch (Stale stale) {
            return result(Status.STALE_SESSION, session);
        }
    }

    private static int origin(int chunk) {
        long value = (long) chunk * 16;
        if (value < Integer.MIN_VALUE || value + 15 > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Captured chunk lies outside integer block coordinates");
        return (int) value;
    }

    private static byte kind(Looks looks, int id) {
        if (id < 0 || id >= looks.kind.length) throw new IllegalArgumentException("Prepared look ID out of bounds");
        // Acquire readiness without invoking make or any appearance callback.
        if (!looks.isPrepared(id)) throw new Pending();
        byte kind = looks.kind[id];
        if (kind < Looks.AIR) throw new Pending();
        if (kind > Looks.PLANT) throw new IllegalArgumentException("Invalid prepared material kind");
        return kind;
    }

    private static byte light(int value) {
        if (value < 0 || value > 15) throw new IllegalArgumentException("Captured light outside 0..15");
        return (byte) value;
    }

    private static void current(Access access, Object session) {
        if (!access.current() || access.session() != session) throw new Stale();
    }

    private static Result result(Status status, @Nullable Object session) {
        return new Result(status, null, session);
    }

    private static final class Pending extends RuntimeException {
    }

    private static final class Stale extends RuntimeException {
    }
}
