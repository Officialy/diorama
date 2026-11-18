package dev.diorama.gtnh.capture;

import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.Facing;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VoxelReader;
import dev.diorama.data.ChunkSnapshot;

/** Bounded numeric preview. Not a scheduler, loaded-world view or complete lighting halo. */
public final class TerrainPreview {

    public static final int MAX_SNAPSHOTS = 9;

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
        return new Work(looks, token, key, Objects.requireNonNull(snapshots, "snapshots"));
    }

    public static boolean supported() {
        return MapStyle.get() == MapStyle.DEFAULT && Projection.facing() == Facing.NORTH
            && !TileBaker.fast()
            && Projection.sin() == Math.sin(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()))
            && Projection.cos() == Math.cos(Math.toRadians(MapStyle.DEFAULT.pitchDegrees()));
    }

    private static void requireSupported() {
        if (!supported())
            throw new IllegalStateException("Preview requires unchanged default style, north facing and full quality");
    }

    private static void checkKey(TileKey key) {
        Objects.requireNonNull(key, "key");
        if (key.layer() != TileKey.SURFACE || key.lod() != 0)
            throw new IllegalArgumentException("Preview requires surface LOD0");
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
        private final int count;
        private final int top;
        private final float[] sunTint;
        private final float[] shadowTint;
        private volatile boolean current = true;

        private Work(PreparedBlockLooks looks, Object token, TileKey key, ChunkSnapshot[] snapshots) {
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
            if (snapshots.length == 0 || snapshots.length > MAX_SNAPSHOTS)
                throw new IllegalArgumentException("Preview requires at most nine snapshot slots");
            this.snapshots = snapshots.clone();
            int n = 0, highest = Integer.MIN_VALUE;
            for (int i = 0; i < this.snapshots.length; i++) {
                ChunkSnapshot snapshot = this.snapshots[i];
                if (snapshot == null) continue;
                n++;
                validate(snapshot);
                for (int j = 0; j < i; j++) {
                    ChunkSnapshot previous = this.snapshots[j];
                    if (previous != null && previous.key() == snapshot.key())
                        throw new IllegalArgumentException("Duplicate preview chunk coordinate");
                }
                highest = Math.max(highest, snapshot.maxY);
            }
            if (n == 0) throw new IllegalArgumentException("Preview requires at least one ready snapshot");
            this.count = n;
            this.top = highest == Integer.MIN_VALUE ? 0 : highest + 1;
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
                for (int id : ids) if (id >= this.looks.kind.length || !this.looks.isPrepared(id))
                    throw new IllegalArgumentException("Preview snapshot contains an unprepared look");
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
            if (!supported() || !Arrays.equals(this.sunTint, MapStyle.DEFAULT.sunTint())
                || !Arrays.equals(this.shadowTint, MapStyle.DEFAULT.shadowTint())) {
                this.current = false;
                return false;
            }
            return true;
        }

        /** Full canonical ABGR tile; missing chunks remain unknown/transparent, never invented air. */
        public TileImage bake() {
            if (!this.stillCurrent()) throw new IllegalStateException("Stale preview projection/style");
            TileImage image = TileBaker.bake(this.key, this, this.looks, null, false);
            if (!this.stillCurrent()) throw new IllegalStateException("Preview projection/style changed while baking");
            return image;
        }

        @Override
        public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
            for (ChunkSnapshot snapshot : this.snapshots)
                if (snapshot != null && snapshot.chunkX == chunkX && snapshot.chunkZ == chunkZ) return snapshot;
            return null;
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
            return 256;
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
