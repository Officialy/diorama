package dev.diorama.gtnh.capture;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.bake.Looks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VoxelReader;
import dev.diorama.data.ChunkSnapshot;

/** Bounded numeric preview. Not a scheduler, loaded-world view or complete lighting halo. */
public final class TerrainPreview {

    public static final int MAX_SNAPSHOTS = 9;
    public static final int MAX_EXTENDED_SNAPSHOTS = 65 * 65;
    private static final int WORLD_TOP = 256;
    private static final int COARSE_STRIDE = Integer.getInteger("diorama.coarseStride", 4) == 2 ? 2
        : Integer.getInteger("diorama.coarseStride", 4) == 8 ? 8 : 4;
    // The canonical player-follow cut can round up just beyond the world storage span.
    private static final int MAX_CAVE_LAYER = WORLD_TOP + 4;

    private TerrainPreview() {}

    /** Uses the current default north-facing projection; never changes global style or tile size. */
    public static TileKey keyFor(double x, double y, double z) {
        requireSupported();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Non-finite preview position");
        double u = Math.floor(Projection.u(x, z) / Projection.TILE_UNITS);
        double v = Math.floor(Projection.v(x, y, z) / Projection.TILE_UNITS);
        if (u < Integer.MIN_VALUE || u > Integer.MAX_VALUE || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Preview tile coordinate overflow");
        TileKey key = new TileKey(TileKey.SURFACE, (int) u, (int) v);
        checkKey(key);
        requireSupported();
        return key;
    }

    public static Work prepare(PreparedBlockLooks looks, Object token, TileKey key,
        ChunkSnapshot @Nullable [] snapshots) {
        return new Work(looks, token, key, Objects.requireNonNull(snapshots, "snapshots"), false);
    }

    /** Full render-distance input: detached snapshot validation is deferred to the bake worker. */
    public static Work prepareExtended(PreparedBlockLooks looks, Object token, TileKey key, ChunkSnapshot[] snapshots) {
        return new Work(looks, token, key, Objects.requireNonNull(snapshots, "snapshots"), true);
    }

    public static boolean supported() {
        return MapStyle.get() == MapStyle.DEFAULT && Projection.facing() == Facing.NORTH
            && Projection.sin() == Math.sin(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()))
            && Projection.cos() == Math.cos(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()));
    }

    private static void requireSupported() {
        if (!supported())
            throw new IllegalStateException("Preview requires unchanged default style and north facing");
    }

    /** Exclusive cave ray ceiling; the raw layer remains part of the cache key. */
    public static int caveCeiling(TileKey key) {
        if (key.layer() <= 0 || key.layer() > MAX_CAVE_LAYER)
            throw new IllegalArgumentException("Expected a cave cut in 1.." + MAX_CAVE_LAYER);
        return Math.min(key.layer(), WORLD_TOP);
    }

    private static void checkKey(TileKey key) {
        Objects.requireNonNull(key, "key");
        if (key.lod() != 0) throw new IllegalArgumentException("Preview requires LOD0");
        if (key.isCave()) caveCeiling(key);
        // Canonical baker pixel origins are ints and include a neighbouring-pixel outline halo.
        for (int tile : new int[] { key.tileU(), key.tileV() }) {
            long origin = (long) tile * Projection.TILE_PIXELS;
            if (origin < (long) Integer.MIN_VALUE + Projection.TILE_PIXELS
                || origin + 2L * Projection.TILE_PIXELS > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Preview raster coordinate overflow");
        }
    }

    /** Only detached snapshots and append-only published numeric looks cross into bake(). */
    public static final class Work implements VoxelReader.Inputs {

        private final PreparedBlockLooks looks;
        private final Object token;
        private final TileKey key;
        private final ChunkSnapshot[] snapshots;
        private final Map<Long, ChunkSnapshot> byCoordinate = new HashMap<>();
        private final boolean extended;
        private final boolean fastQuality;
        private final int count;
        private final int top;
        private final float[] sunTint;
        private final float[] shadowTint;
        private volatile boolean current = true;

        private Work(PreparedBlockLooks looks, Object token, TileKey key, ChunkSnapshot[] snapshots, boolean extended) {
            this.extended = extended;
            this.fastQuality = TileBaker.fast();
            this.looks = Objects.requireNonNull(looks, "looks");
            this.token = Objects.requireNonNull(token, "token");
            if (looks.session() != token) throw new IllegalArgumentException("Preview token does not own looks");
            checkKey(key);
            requireSupported();
            this.key = key;
            this.sunTint = MapStyle.DEFAULT.sunTint()
                .clone();
            this.shadowTint = MapStyle.DEFAULT.shadowTint()
                .clone();
            if (snapshots.length == 0 || snapshots.length > (extended ? MAX_EXTENDED_SNAPSHOTS : MAX_SNAPSHOTS))
                throw new IllegalArgumentException("Invalid preview snapshot count");
            this.snapshots = snapshots.clone();
            int n = 0, highest = Integer.MIN_VALUE;
            for (int i = 0; i < this.snapshots.length; i++) {
                ChunkSnapshot snapshot = this.snapshots[i];
                if (snapshot == null) continue;
                n++;
                if (!extended) validate(snapshot);
                else if (snapshot.lookDomain() != this.looks || snapshot.airId() != this.looks.airId()
                    || snapshot.minSectionY != 0
                    || snapshot.sectionCount() != 16)
                    throw new IllegalArgumentException("Invalid extended snapshot domain or height");
                if (this.byCoordinate.put(snapshot.key(), snapshot) != null)
                    throw new IllegalArgumentException("Duplicate preview chunk coordinate");
                highest = Math.max(highest, snapshot.maxY);
            }
            if (n == 0) throw new IllegalArgumentException("Preview requires at least one ready snapshot");
            this.count = n;
            this.top = key.isCave() ? caveCeiling(key) : highest == Integer.MIN_VALUE ? 0 : highest + 1;
            if (!this.stillCurrent()) throw new IllegalStateException("Preview style changed during preparation");
        }

        private void validate(ChunkSnapshot snapshot) {
            if (snapshot.lookDomain() != this.looks || snapshot.airId() != this.looks.airId())
                throw new IllegalArgumentException("Preview snapshot air/look domain mismatch");
            if (snapshot.minSectionY != 0 || snapshot.sectionCount() != 16)
                throw new IllegalArgumentException("Preview snapshot must cover 0..256");
            for (int coordinate : new int[] { snapshot.chunkX, snapshot.chunkZ }) {
                long origin = (long) coordinate * 16;
                if (origin < Integer.MIN_VALUE || origin + 15 > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Preview chunk block coordinate overflow");
            }
            int highest = Integer.MIN_VALUE;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int y = snapshot.surface(x, z);
                if (y != Integer.MIN_VALUE && (y < 0 || y > 255))
                    throw new IllegalArgumentException("Invalid preview surface height");
                highest = Math.max(highest, y);
            }
            if (snapshot.maxY != highest) throw new IllegalArgumentException("Preview maxY disagrees with column tops");
            for (int section = 0; section < 16; section++) {
                int[] ids = snapshot.sectionIds(section);
                if (ids == null) continue;
                for (int id : ids) {
                    if (id != ChunkSnapshot.UNKNOWN_LOOK && !this.looks.isPrepared(id))
                        throw new IllegalArgumentException("Preview snapshot contains an unprepared look");
                }
            }
        }

        public TileKey key() {
            return this.key;
        }

        public Object token() {
            return this.token;
        }

        public PreparedBlockLooks looks() {
            return this.looks;
        }

        public int snapshotCount() {
            return this.count;
        }

        /** Projection/style predicate only; the native owner must independently revalidate its epoch. */
        public boolean stillCurrent() {
            if (!this.current) return false;
            if (!supported() || this.fastQuality != TileBaker.fast()
                || !Arrays.equals(this.sunTint, MapStyle.DEFAULT.sunTint())
                || !Arrays.equals(this.shadowTint, MapStyle.DEFAULT.shadowTint())) {
                this.current = false;
                return false;
            }
            return true;
        }

        /** Full canonical ABGR tile; missing chunks remain unknown/transparent, never invented air. */
        public TileImage bake() {
            return this.bake(null);
        }

        /** Full HUD L0 refresh. An existing raster can become explicitly transparent, not absent. */
        public int @Nullable [] bakeDetail(int @Nullable [] history) {
            return this.bakeDetail(history, false);
        }

        public int @Nullable [] bakeDetail(int @Nullable [] history, boolean refined) {
            if (history != null) checkRaster(history);
            int[] fresh = Objects.requireNonNull(
                this.bake(null, refined)
                    .mips(),
                "full tile")[0];
            if (history == null || this.top != this.minY()) return PreviewTileStore.retain(history, fresh);
            int[] merged = history.clone();
            this.mergeDetail(merged, fresh, 0, 0, Projection.TILE_PIXELS);
            return merged;
        }

        /**
         * Updates only the requested 32px blocks (row-major tile coordinates). The returned full
         * L0 raster is detached from read-only history; it can be paired with the same mask.
         */
        public int[] bakeDetailPatches(int[] history, BitSet patches) {
            return this.bakeDetailPatches(history, patches, false);
        }

        public int[] bakeDetailPatches(int[] history, BitSet patches, boolean refined) {
            if (this.key.isCave()) throw new IllegalArgumentException("Cave previews require a full tile refresh");
            checkRaster(history);
            int perRow = Projection.TILE_PIXELS / TileImage.BLOCK;
            if (patches.isEmpty() || patches.length() > perRow * perRow)
                throw new IllegalArgumentException("Expected nonempty preview patch mask within the tile");
            TileImage image = this.bake(patches, refined);
            int[] merged = history.clone();
            for (TileImage.Patch patch : image.patches()) {
                this.mergeDetail(
                    merged,
                    patch.mips()[0],
                    patch.blockX() * TileImage.BLOCK,
                    patch.blockY() * TileImage.BLOCK,
                    TileImage.BLOCK);
            }
            return merged;
        }

        public static final int GPU_COLUMNS = 128, GPU_ROWS = 400;

        /** Detached surface height/colour texture for the approximate GPU preview. */
        public float[] gpuColumns() {
            this.validateForBake();
            if (this.key.isCave()) throw new IllegalStateException("GPU surface preview only");
            float[] columns = new float[GPU_COLUMNS * GPU_ROWS * 4];
            for (int i = 3; i < columns.length; i += 4) columns[i] = -2;
            int x0 = (int) this.key.minU(), z0 = (int) Math.floor(this.key.minV() / Projection.sin());
            for (ChunkSnapshot snapshot : this.snapshots) {
                if (snapshot == null) continue;
                int[][] ids = new int[16][];
                boolean[] loaded = new boolean[16];
                ChunkSnapshot.Tints tints = snapshot.bakeTints();
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int u = snapshot.chunkX * 16 + x - x0, v = snapshot.chunkZ * 16 + z - z0;
                    if (u < 0 || u >= GPU_COLUMNS || v < 0 || v >= GPU_ROWS) continue;
                    int i = (v * GPU_COLUMNS + u) * 4;
                    int y = snapshot.surface(x, z);
                    if (y < 0 || y >= 256) { columns[i + 3] = -1; continue; }
                    int section = y >> 4;
                    if (!loaded[section]) {
                        ids[section] = snapshot.sectionIds(section);
                        loaded[section] = true;
                    }
                    int[] states = ids[section];
                    if (states == null) continue;
                    int id = states.length == 1 ? states[0] : states[((y & 15) << 8) | (z << 4) | x];
                    if (id < 0 || !this.looks.isPrepared(id) || this.looks.kind[id] == Looks.AIR) continue;
                    int tint = this.looks.constantTint[id];
                    int col = (z << 4) | x;
                    if (tint < 0) tint = switch (this.looks.tint[id]) {
                        case Looks.TINT_GRASS -> tints.grass()[col];
                        case Looks.TINT_FOLIAGE -> tints.foliage()[col];
                        case Looks.TINT_DRY_FOLIAGE -> tints.dryFoliage()[col];
                        case Looks.TINT_WATER -> tints.water()[col];
                        default -> 0xFFFFFF;
                    };
                    float weight = this.looks.topTintWeight[id];
                    for (int channel = 0; channel < 3; channel++) {
                        int shift = 16 - channel * 8;
                        float plain = ((this.looks.top[id] >> shift) & 255) / 255F;
                        float tinted = ((this.looks.topTinted[id] >> shift) & 255) / 255F;
                        float multiplier = ((tint >> shift) & 255) / 255F;
                        columns[i + channel] = plain * (1 - weight) + tinted * multiplier * weight;
                    }
                    columns[i + 3] = y + 1;
                }
            }
            return columns;
        }

        public int[] bakeHighResolution() {
            this.validateForBake();
            int[] raster = TileBaker.bakeHighResolution(this.key, this, this.looks);
            if (!this.stillCurrent()) throw new IllegalStateException("Stale high-resolution preview");
            return raster;
        }

        /** Only fill gaps in existing confirmed detail; provisional samples must never replace it. */
        public int @Nullable [] bakeCoarse(int @Nullable [] history) {
            if (history != null) checkRaster(history);
            this.validateForBake();
            int[] fresh = TileBaker.bakeCoarse(this.key, this, this.looks, COARSE_STRIDE);
            if (!this.stillCurrent()) throw new IllegalStateException("Preview projection/style changed while baking");
            return PreviewTileStore.retain(fresh, history);
        }

        private void validateForBake() {
            if (!this.stillCurrent()) throw new IllegalStateException("Stale preview projection/style");
            if (this.extended) for (ChunkSnapshot snapshot : this.snapshots) if (snapshot != null) validate(snapshot);
        }

        private TileImage bake(@Nullable BitSet patches) {
            return this.bake(patches, false);
        }

        private TileImage bake(@Nullable BitSet patches, boolean refined) {
            this.validateForBake();
            // Cave keys take the canonical fixed cut path. There is no visited-voxel mask or
            // artificial roof/lift here; missing chunks remain unknown to the baker.
            TileImage image = refined ? TileBaker.bakeRefined(this.key, this, this.looks, patches)
                : TileBaker.bake(this.key, this, this.looks, patches, false);
            if (!this.stillCurrent()) throw new IllegalStateException("Preview projection/style changed while baking");
            return image;
        }

        private static void checkRaster(int[] history) {
            if (history.length != Projection.TILE_PIXELS * Projection.TILE_PIXELS)
                throw new IllegalArgumentException("Expected full preview history raster");
        }

        private void mergeDetail(int[] merged, int[] fresh, int x0, int y0, int edge) {
            for (int y = 0; y < edge; y++) {
                int dst = (y0 + y) * Projection.TILE_PIXELS + x0;
                for (int x = 0; x < edge; x++) {
                    int pixel = fresh[y * edge + x];
                    if ((pixel >>> 24) != 0 || ((merged[dst + x] >>> 24) != 0 && this.knownEmpty(x0 + x, y0 + y)))
                        merged[dst + x] = pixel;
                }
            }
        }

        private boolean knownEmpty(int x, int y) {
            // Zero normally means no ray was known. If all ready chunks are empty, TileBaker
            // exits before tracing. Clear only where the entire pixel footprint at the start
            // plane (world bottom) belongs to ready chunks; missing edges still retain history.
            if (this.top != this.minY()) return false;
            double step = 1.0 / Projection.PIXELS_PER_UNIT;
            int cx = (int) Math.floor(this.key.minU() + x * step) >> 4;
            double v = this.key.minV() + y * step;
            // Use the supported pitch, not a global projection that can change after bake().
            double invSin = 1.0 / Math.sin(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()));
            int north = (int) Math.floor(v * invSin) >> 4;
            int south = (int) Math.floor((v + step) * invSin) >> 4;
            return this.chunk(cx, north) != null && (north == south || this.chunk(cx, south) != null);
        }

        @Override
        public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
            return this.byCoordinate.get(ChunkSnapshot.key(chunkX, chunkZ));
        }

        @Override
        public int airId() {
            return this.looks.airId();
        }

        @Override
        public Object lookDomain() {
            return this.looks;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return WORLD_TOP;
        }

        @Override
        public int topY() {
            return this.top;
        }

        @Override
        public boolean isSeen(int x, int y, int z) {
            return true;
        }
    }
}
