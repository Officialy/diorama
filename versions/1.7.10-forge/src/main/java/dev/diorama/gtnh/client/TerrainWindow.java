package dev.diorama.gtnh.client;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.TerrainMetrics;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Main-thread capture and change coalescing shared by the map and HUD. */
final class TerrainWindow {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    static final int CAPACITY = 4096;
    private final ClientCaptureLifecycle.Session session;
    private final BooleanSupplier accepts;
    private final TerrainPreviewRefresh refresh;
    private final @Nullable BiConsumer<Integer, Integer> availabilityChanged;
    private final ChunkSnapshot[] snapshots = new ChunkSnapshot[TerrainPreviewRefresh.SLOTS];
    private final ChunkCapture.Status[] statuses = new ChunkCapture.Status[TerrainPreviewRefresh.SLOTS];
    private int discoveryTicks, ready, pending, missing;
    private int appearanceCooldown;

    TerrainWindow(ClientCaptureLifecycle.Session session, int cx, int cz, BooleanSupplier accepts,
        @Nullable TerrainWindow from) {
        this(session, cx, cz, accepts, from, null);
    }

    TerrainWindow(ClientCaptureLifecycle.Session session, int cx, int cz, BooleanSupplier accepts,
        @Nullable TerrainWindow from, @Nullable BiConsumer<Integer, Integer> availabilityChanged) {
        this.availabilityChanged = availabilityChanged;
        this.session = session;
        this.accepts = accepts;
        this.refresh = new TerrainPreviewRefresh(cx, cz, System.nanoTime());
        if (from != null && from.session == session) {
            discoveryTicks = from.discoveryTicks;
            appearanceCooldown = from.appearanceCooldown;
            for (int slot = 0; slot < TerrainPreviewRefresh.SLOTS; slot++) {
                int old = from.refresh.slot(refresh.x(slot), refresh.z(slot));
                if (old < 0) continue;
                snapshots[slot] = from.snapshots[old];
                statuses[slot] = from.statuses[old];
                refresh.inherit(slot, from.refresh, old);
            }
        }
        count();
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
        return key.isCave() ? TerrainPreview.caveCeiling(key) : topY();
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
        return TerrainPreview.prepare(session.looks(), session.token(), key, snapshots);
    }

    boolean tick(int budget) {
        requireCurrent();
        long now = System.nanoTime();
        if (appearanceCooldown > 0) appearanceCooldown--;
        if (appearanceCooldown == 0 && LegacyGeometryCapture.consumeChanged()) {
            appearanceCooldown = 20;
            for (int slot = 0; slot < TerrainPreviewRefresh.SLOTS; slot++) {
                if (statuses[slot] != ChunkCapture.Status.MISSING_CHUNK) {
                    refresh.request(slot, now, TerrainPreviewRefresh.CHANGE);
                    if (availabilityChanged != null) availabilityChanged.accept(refresh.x(slot), refresh.z(slot));
                }
            }
        }
        if (++discoveryTicks == TerrainPreviewRefresh.DISCOVERY_TICKS) {
            discoveryTicks = 0;
            for (int slot = 0; slot < TerrainPreviewRefresh.SLOTS; slot++) {
                if (statuses[slot] != ChunkCapture.Status.MISSING_CHUNK || refresh.queued(slot)) continue;
                boolean loaded = session.access()
                    .loaded(refresh.x(slot), refresh.z(slot)) != null;
                requireCurrent();
                if (loaded && !refresh.queued(slot)) refresh.request(slot, now, 0);
            }
        }
        int attempted = 0;
        boolean changed = false;
        for (; budget > 0; budget--) {
            int slot = refresh.takeDue(now, attempted);
            if (slot < 0) break;
            attempted |= 1 << slot;
            changed |= captureSlot(slot);
        }
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
        if (slot >= 0 && statuses[slot] != ChunkCapture.Status.MISSING_CHUNK) refresh.request(slot, now, delay);
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
        if (availabilityChanged != null && (snapshots[slot] == null) != (snapshot == null))
            availabilityChanged.accept(cx, cz);
        snapshots[slot] = snapshot;
        statuses[slot] = outcome;
        count();
        return true;
    }

    private void count() {
        ready = pending = missing = 0;
        for (ChunkCapture.Status state : statuses) {
            if (state == ChunkCapture.Status.READY) ready++;
            else if (state == ChunkCapture.Status.MISSING_CHUNK) missing++;
            else pending++;
        }
    }

}
