package dev.diorama.bake;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.data.ChunkSnapshot;

/** Prepared numeric snapshots adapted to the canonical reader; never reads a live world. */
public final class VoxelReader extends VoxelReaderCore {

    public static final int UNKNOWN = VoxelReaderCore.UNKNOWN;
    static final boolean DEBUG = false;

    /**
     * Stable world bounds and explicit look/session identity for this job. Every present snapshot
     * must contain every section intersecting [minY, maxY); absent sections within it are known air.
     */
    public interface Inputs extends ChunkSource {

        int airId();

        Object lookDomain();
    }

    private final ChunkSource source;
    private final @Nullable Looks looks;
    private final Object lookDomain;
    private final DecodedSections.Air air;
    private final int minSectionY;
    private final int sectionCount;

    VoxelReader(ChunkSource source) {
        this(source, null);
    }

    public VoxelReader(ChunkSource source, @Nullable Looks looks) {
        this(source, looks, false);
    }

    public VoxelReader(ChunkSource source, @Nullable Looks looks, boolean shapes) {
        this(source, looks, shapes, inputs(source));
    }

    private VoxelReader(ChunkSource source, @Nullable Looks looks, boolean shapes, Inputs inputs) {
        super(
            source.minY(),
            source.maxY(),
            inputs.airId(),
            looks == null ? null : looks.kind,
            looks == null || !shapes ? null : looks.shape,
            DEBUG);
        this.source = source;
        this.looks = looks;
        if (source.maxY() <= source.minY()) throw new IllegalArgumentException("Empty prepared world height range");
        this.minSectionY = source.minY() >> 4;
        this.sectionCount = ((source.maxY() - 1) >> 4) - this.minSectionY + 1;
        this.lookDomain = Objects.requireNonNull(inputs.lookDomain(), "lookDomain");
        if (looks != null && this.lookDomain != looks)
            throw new IllegalArgumentException("Prepared look domain does not match Looks identity");
        if (this.airId() < 0) throw new IllegalArgumentException("Negative air ID");
        this.air = new DecodedSections.Air(this.airId());
        if (looks != null) {
            if (this.airId() >= looks.kind.length || looks.kind[looks.ensure(this.airId())] != Looks.AIR) {
                throw new IllegalArgumentException("Prepared air ID does not identify air in this look domain");
            }
        }
    }

    private static Inputs inputs(ChunkSource source) {
        if (!(source instanceof Inputs inputs)) throw new IllegalArgumentException(
            "Prepared VoxelReader requires VoxelReader.Inputs with explicit air/session identity");
        return inputs;
    }

    @Override
    protected @Nullable DecodedSections loadChunk(int chunkX, int chunkZ) {
        ChunkSnapshot snapshot = this.source.chunk(chunkX, chunkZ);
        if (snapshot == null) return null;
        if (snapshot.chunkX != chunkX || snapshot.chunkZ != chunkZ)
            throw new IllegalArgumentException("Prepared snapshot chunk coordinates mismatch");
        if (snapshot.airId() != this.airId() || snapshot.lookDomain() != this.lookDomain) {
            throw new IllegalArgumentException("Prepared snapshot belongs to another air/look domain");
        }
        if (snapshot.minSectionY != this.minSectionY || snapshot.sectionCount() != this.sectionCount) {
            throw new IllegalArgumentException("Prepared snapshot does not cover the complete world section span");
        }
        return new Decoded(snapshot, this.looks, this.air);
    }

    @Override
    protected int readBlockLight(DecodedSections chunk, int sectionIndex, int x, int y, int z) {
        return ((Decoded) chunk).snapshot.blockLight(sectionIndex, x, y, z);
    }

    @Override
    protected int readSkyLight(DecodedSections chunk, int sectionIndex, int x, int y, int z) {
        return ((Decoded) chunk).snapshot.skyLight(sectionIndex, x, y, z);
    }

    /** Same pinned cache as block, mask and light queries, including pinned absence. */
    @Nullable
    ChunkSnapshot snapshot(int x, int z) {
        Decoded chunk = (Decoded) this.chunk(x >> 4, z >> 4);
        return chunk == null ? null : chunk.snapshot;
    }

    private static final class Decoded extends DecodedSections {

        private final ChunkSnapshot snapshot;
        private final @Nullable Looks looks;

        Decoded(ChunkSnapshot snapshot, @Nullable Looks looks, Air air) {
            super(snapshot.sectionCount(), snapshot.minSectionY, air);
            this.snapshot = snapshot;
            this.looks = looks;
        }

        @Override
        protected Section read(int index) {
            int[] ids = this.snapshot.sectionIds(index);
            if (ids == null) return this.air.section;
            if (this.looks != null) {
                for (int id : ids) {
                    if (id >= this.looks.kind.length)
                        throw new IllegalArgumentException("Prepared ID outside look domain");
                    this.looks.ensure(id);
                }
            }
            return ids.length == 1 ? new Section(ids[0], null, null, null, null)
                : new Section(-1, ids, null, null, null);
        }

        @Override
        protected int[] byBlock(int index) {
            throw new AssertionError("Prepared sections already contain numeric IDs");
        }

        @Override
        protected void fillSolidByBlock(int index, byte[] kinds, int @Nullable [] shapes) {
            throw new AssertionError("Prepared sections never use the by-block decoding path");
        }
    }
}
