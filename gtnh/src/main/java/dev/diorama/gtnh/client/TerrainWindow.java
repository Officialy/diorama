package dev.diorama.gtnh.client;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.init.Blocks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Main-thread capture and change coalescing shared by the map and HUD. */
final class TerrainWindow {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    static final int CAPACITY = 4096;
    private final ClientCaptureLifecycle.Session session;
    private final BooleanSupplier accepts;
    private final TerrainPreviewRefresh refresh;
    private final ChunkSnapshot[] snapshots = new ChunkSnapshot[TerrainPreviewRefresh.SLOTS];
    private final ChunkCapture.Status[] statuses = new ChunkCapture.Status[TerrainPreviewRefresh.SLOTS];
    private int discoveryTicks, ready, pending, missing;

    TerrainWindow(ClientCaptureLifecycle.Session session, int cx, int cz, BooleanSupplier accepts,
        @Nullable TerrainWindow from) {
        this.session = session;
        this.accepts = accepts;
        this.refresh = new TerrainPreviewRefresh(cx, cz, System.nanoTime());
        if (from != null && from.session == session) {
            discoveryTicks = from.discoveryTicks;
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
        try {
            ChunkCapture.Chunk chunk = session.access()
                .loaded(cx, cz);
            requireCurrent();
            if (chunk == null) outcome = ChunkCapture.Status.MISSING_CHUNK;
            else if (preflight(chunk)) {
                ChunkCapture.Result result = session.capture(cx, cz);
                requireCurrent();
                if (result.status() == ChunkCapture.Status.READY && ClientCaptureLifecycle.accepts(result)) {
                    snapshot = result.snapshot();
                    outcome = ChunkCapture.Status.READY;
                } else if (result.status() == ChunkCapture.Status.MISSING_CHUNK) {
                    outcome = ChunkCapture.Status.MISSING_CHUNK;
                }
            }
        } catch (RuntimeException error) {
            requireCurrent();
            LOG.warn("Diorama terrain capture pending at ({}, {})", cx, cz, error);
        }
        requireCurrent();
        if (version != refresh.version(slot)) return false; // Reentrant event owns the next attempt.
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

    private boolean preflight(ChunkCapture.Chunk chunk) {
        requireCurrent();
        int minimum = chunk.minSectionY();
        requireCurrent();
        int count = chunk.sectionCount();
        requireCurrent();
        if (minimum != 0 || count != 16) return false;
        Set<Long> states = new HashSet<>();
        for (int index = 0; index < 16; index++) {
            ChunkCapture.Section section = chunk.section(index);
            requireCurrent();
            if (section == null) continue;
            for (int cell = 0; cell < 4096; cell++) {
                int raw = section.blockId(cell);
                requireCurrent();
                int meta = section.metadata(cell);
                requireCurrent();
                if (raw < 0 || raw > 0xFFFFFF || meta < 0 || meta > 65535) return false;
                states.add(((long) raw << 16) | meta);
                if (states.size() > CAPACITY) return false;
            }
        }
        for (long state : states) {
            requireCurrent();
            int raw = (int) (state >>> 16), meta = (int) (state & 65535);
            Block block = Block.getBlockById(raw);
            if (block == null || Block.getIdFromBlock(block) != raw) return false;
            boolean prepared;
            if (block == Blocks.grass && block.getClass() == BlockGrass.class && meta == 0) {
                prepared = session.prepare(raw, meta, GrassContext.NORMAL) >= 0;
                requireCurrent();
                prepared &= session.prepare(raw, meta, GrassContext.SNOWY) >= 0;
            } else prepared = session.prepare(raw, meta) >= 0;
            requireCurrent();
            if (!prepared) return false;
        }
        return true;
    }

}
