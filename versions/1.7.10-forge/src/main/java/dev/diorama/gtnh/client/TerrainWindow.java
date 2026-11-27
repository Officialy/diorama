package dev.diorama.gtnh.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.TerrainMetrics;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Main-thread capture and change coalescing shared by the map and HUD. */
final class TerrainWindow {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    // 32K states cost roughly 1.3 MiB for the base primitive arrays, before maps/shapes.
    static final int CAPACITY = 32768;
    private final ClientCaptureLifecycle.Session session;
    private final BooleanSupplier accepts;
    private final TerrainPreviewRefresh refresh;
    private final @Nullable BiConsumer<Integer, Integer> availabilityChanged;
    private final ChunkSnapshot[] snapshots;
    private final ChunkCapture.Status[] statuses;
    private int discoveryCursor, ready, pending, missing;
    private int appearanceCooldown;

    TerrainWindow(ClientCaptureLifecycle.Session session, int cx, int cz, BooleanSupplier accepts,
        @Nullable TerrainWindow from) {
        this(session, cx, cz, accepts, from, null);
    }

    TerrainWindow(ClientCaptureLifecycle.Session session, int cx, int cz, BooleanSupplier accepts,
        @Nullable TerrainWindow from, @Nullable BiConsumer<Integer, Integer> availabilityChanged) {
        this(session, cx, cz, 1, accepts, from, availabilityChanged);
    }

    TerrainWindow(ClientCaptureLifecycle.Session session, int cx, int cz, int radius, BooleanSupplier accepts,
        @Nullable TerrainWindow from, @Nullable BiConsumer<Integer, Integer> availabilityChanged) {
        this.availabilityChanged = availabilityChanged;
        this.session = session;
        this.accepts = accepts;
        this.refresh = new TerrainPreviewRefresh(cx, cz, radius, System.nanoTime());
        snapshots = new ChunkSnapshot[refresh.slots()];
        statuses = new ChunkCapture.Status[refresh.slots()];
        if (from != null && from.session == session) {
            appearanceCooldown = from.appearanceCooldown;
            for (int slot = 0; slot < refresh.slots(); slot++) {
                int old = from.refresh.slot(refresh.x(slot), refresh.z(slot));
                if (old < 0) continue;
                snapshots[slot] = from.snapshots[old];
                statuses[slot] = from.statuses[old];
                refresh.inherit(slot, from.refresh, old);
            }
        }
        count();
    }

    int radius() {
        return refresh.radius();
    }

    boolean contains(int cx, int cz) {
        return refresh.slot(cx, cz) >= 0;
    }

    boolean near(int cx, int cz) {
        for (int z = cz - 1; z <= cz + 1; z++) for (int x = cx - 1; x <= cx + 1; x++) if (contains(x, z)) return true;
        return false;
    }

    int topY() {
        int top = Integer.MIN_VALUE;
        for (ChunkSnapshot snapshot : snapshots) if (snapshot != null) top = Math.max(top, snapshot.maxY);
        return top == Integer.MIN_VALUE ? 0 : top + 1;
    }

    /** Bake identity height: captured surface top, or the selected cave's exclusive cut. */
    int topY(TileKey key) {
        if (key.isCave()) return TerrainPreview.caveCeiling(key);
        int top = Integer.MIN_VALUE;
        for (ChunkSnapshot snapshot : snapshots) {
            if (snapshot != null && intersects(key, snapshot)) top = Math.max(top, snapshot.maxY);
        }
        return top == Integer.MIN_VALUE ? 0 : top + 1;
    }

    int ready() {
        return ready;
    }

    int pendingCount() {
        return pending;
    }

    int missing() {
        return missing;
    }

    boolean queued() {
        return refresh.pending();
    }

    long revision() {
        return refresh.revision();
    }

    TerrainPreview.Work prepare(TileKey key) {
        requireCurrent();
        List<ChunkSnapshot> input = new ArrayList<>();
        for (ChunkSnapshot snapshot : snapshots) {
            if (snapshot != null && intersects(key, snapshot)) input.add(snapshot);
        }
        return TerrainPreview
            .prepareExtended(session.looks(), session.token(), key, input.toArray(new ChunkSnapshot[0]));
    }

    private static boolean intersects(TileKey key, ChunkSnapshot snapshot) {
        return DirtyTerrainTiles.intersectsChunk(key, snapshot.chunkX, snapshot.chunkZ);
    }

    boolean hasReady(TileKey key) {
        for (ChunkSnapshot snapshot : snapshots) if (snapshot != null && intersects(key, snapshot)) return true;
        return false;
    }

    /** Conservative projected footprint of captured chunks, including the vertical ray span. */
    List<TileKey> candidates(int layer) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        int top = 0;
        for (ChunkSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            x0 = Math.min(x0, snapshot.chunkX * 16);
            z0 = Math.min(z0, snapshot.chunkZ * 16);
            x1 = Math.max(x1, snapshot.chunkX * 16 + 15);
            z1 = Math.max(z1, snapshot.chunkZ * 16 + 15);
            top = Math.max(top, snapshot.maxY + 1);
        }
        List<TileKey> keys = new ArrayList<>();
        if (x0 > x1) return keys;
        if (layer != TileKey.SURFACE) top = Math.min(256, layer);
        int u0 = (int) Math.floor(Projection.u(x0, z0) / Projection.TILE_UNITS);
        int u1 = (int) Math.floor(Projection.u(x1 + 1, z1 + 1) / Projection.TILE_UNITS);
        int v0 = (int) Math.floor(Projection.v(x0, top, z0) / Projection.TILE_UNITS);
        int v1 = (int) Math.floor(Projection.v(x1 + 1, 0, z1 + 1) / Projection.TILE_UNITS);
        for (int v = v0; v <= v1; v++) for (int u = u0; u <= u1; u++) keys.add(new TileKey(layer, u, v));
        return keys;
    }

    boolean tick(int budget) {
        return tick(budget, Long.MAX_VALUE);
    }

    boolean tick(int budget, long nanos) {
        requireCurrent();
        long now = System.nanoTime();
        if (appearanceCooldown > 0) appearanceCooldown--;
        if (appearanceCooldown == 0 && LegacyGeometryCapture.consumeChanged()) {
            appearanceCooldown = 20;
            for (int slot = 0; slot < refresh.slots(); slot++) {
                if (snapshots[slot] != null) {
                    refresh.request(slot, now, TerrainPreviewRefresh.CHANGE);
                }
            }
        }
        // Poll missing slots incrementally; packet notifications still accelerate new arrivals.
        int probes = Math.max(
            1,
            (refresh.slots() + TerrainPreviewRefresh.DISCOVERY_TICKS - 1) / TerrainPreviewRefresh.DISCOVERY_TICKS);
        for (int i = 0; i < probes; i++) {
            int slot = discoveryCursor++ % refresh.slots();
            if (discoveryCursor == refresh.slots()) discoveryCursor = 0;
            if (refresh.queued(slot)) continue;
            if (statuses[slot] != ChunkCapture.Status.MISSING_CHUNK
                && statuses[slot] != ChunkCapture.Status.PENDING_APPEARANCE) continue;
            if (session.access()
                .hasLoadedChunk(refresh.x(slot), refresh.z(slot))) refresh.request(slot, now, 0);
            requireCurrent();
        }
        BitSet attempted = new BitSet();
        boolean changed = false;
        for (; budget > 0; budget--) {
            if (!attempted.isEmpty() && System.nanoTime() - now >= nanos) break;
            int slot = refresh.takeDue(System.nanoTime(), attempted);
            if (slot < 0) break;
            attempted.set(slot);
            changed |= captureSlot(slot);
        }
        if (!attempted.isEmpty()) count();
        return changed;
    }

    private void requireCurrent() {
        if (!accepts.getAsBoolean()) throw new IllegalStateException("Terrain window no longer current");
    }

    void blockChanged(int cx, int cz, int localX, int localZ) {
        long now = System.nanoTime();
        refresh.request(refresh.slot(cx, cz), now, TerrainPreviewRefresh.CHANGE);
        // Actual edits on the border can affect the preview's adjacent shading. This is not
        // a renderer dirty-range or a neighbour update notification.
        int dx = localX == 0 ? -1 : localX == 15 ? 1 : 0;
        int dz = localZ == 0 ? -1 : localZ == 15 ? 1 : 0;
        if (dx != 0) requestRecorded(cx + dx, cz, now, TerrainPreviewRefresh.CHANGE);
        if (dz != 0) requestRecorded(cx, cz + dz, now, TerrainPreviewRefresh.CHANGE);
        if (dx != 0 && dz != 0) requestRecorded(cx + dx, cz + dz, now, TerrainPreviewRefresh.CHANGE);
    }

    void chunkFilled(int cx, int cz, boolean full) {
        long now = System.nanoTime();
        long delay = full ? TerrainPreviewRefresh.LOAD : TerrainPreviewRefresh.CHANGE;
        refresh.request(refresh.slot(cx, cz), now, delay);
        // Whole/partial packet writes may also replace light; full packets replace the biome
        // columns used by already recorded neighbours to blend their one-block tint halo.
        requestNeighbours(cx, cz, now, delay);
    }

    void chunkUnloaded(int cx, int cz) {
        long now = System.nanoTime();
        refresh.request(refresh.slot(cx, cz), now, 0);
        requestNeighbours(cx, cz, now, TerrainPreviewRefresh.CHANGE);
    }

    private void requestNeighbours(int cx, int cz, long now, long delay) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dz != 0) requestRecorded(cx + dx, cz + dz, now, delay);
        }
    }

    private void requestRecorded(int cx, int cz, long now, long delay) {
        int slot = refresh.slot(cx, cz);
        if (slot >= 0 && snapshots[slot] != null) refresh.request(slot, now, delay);
    }

    private boolean captureSlot(int slot) {
        long version = refresh.version(slot);
        ChunkSnapshot snapshot = null;
        ChunkCapture.Status outcome = ChunkCapture.Status.PENDING_APPEARANCE;
        int cx = refresh.x(slot), cz = refresh.z(slot);
        long start = TerrainMetrics.ENABLED ? System.nanoTime() : 0;
        try {
            ChunkCapture.Result result = session.capture(cx, cz);
            requireCurrent();
            outcome = result.status();
            if (outcome == ChunkCapture.Status.READY) {
                if (ClientCaptureLifecycle.accepts(result)) snapshot = result.snapshot();
                else outcome = ChunkCapture.Status.STALE_SESSION;
            }
        } catch (RuntimeException error) {
            requireCurrent();
            if (TerrainMetrics.ENABLED) TerrainMetrics.add(TerrainMetrics.Counter.CAPTURE_FAILED, 1);
            LOG.warn("Diorama terrain capture pending at ({}, {})", cx, cz, error);
        } finally {
            if (TerrainMetrics.ENABLED)
                TerrainMetrics.record(TerrainMetrics.Phase.CAPTURE_SLOT, System.nanoTime() - start);
        }
        requireCurrent();
        if (version != refresh.version(slot)) {
            if (TerrainMetrics.ENABLED) TerrainMetrics.add(TerrainMetrics.Counter.CAPTURE_STALE, 1);
            return false; // Reentrant event owns the next attempt.
        }
        if (TerrainMetrics.ENABLED) TerrainMetrics.add(switch (outcome) {
            case READY -> TerrainMetrics.Counter.CAPTURE_READY;
            case MISSING_CHUNK -> TerrainMetrics.Counter.CAPTURE_MISSING;
            case PENDING_APPEARANCE -> TerrainMetrics.Counter.CAPTURE_PENDING;
            case STALE_SESSION -> TerrainMetrics.Counter.CAPTURE_STALE;
        }, 1);
        // A transient unresolved appearance must not remove the last usable snapshot.
        if (outcome == ChunkCapture.Status.PENDING_APPEARANCE && snapshots[slot] != null) {
            statuses[slot] = outcome;
            return false;
        }
        if (availabilityChanged != null && snapshot != null) availabilityChanged.accept(cx, cz);
        snapshots[slot] = snapshot;
        statuses[slot] = outcome;
        return true;
    }

    private void count() {
        ready = pending = missing = 0;
        for (int slot = 0; slot < statuses.length; slot++) {
            if (snapshots[slot] != null) ready++;
            if (statuses[slot] == ChunkCapture.Status.MISSING_CHUNK) missing++;
            else if (statuses[slot] != ChunkCapture.Status.READY) pending++;
        }
    }

}
