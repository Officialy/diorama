package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.PreviewTileStore;
import dev.diorama.gtnh.capture.TerrainPreview;
import dev.diorama.gtnh.diagnostic.ProbePixels;

/** Event-driven preview, fixed to the opening tile and nine chunk coordinates. */
public final class TerrainPreviewScreen extends GuiScreen {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final int CAPACITY = 4096;
    private final WorldClient originatingWorld;
    private final ChunkSnapshot[] snapshots = new ChunkSnapshot[TerrainPreviewRefresh.SLOTS];
    private final ChunkCapture.Status[] statuses = new ChunkCapture.Status[TerrainPreviewRefresh.SLOTS];
    private ClientCaptureLifecycle.Session session;
    private TerrainPreviewRefresh refresh;
    private TileKey key;
    private PreviewTileStore store;
    private float[] sunTint, shadowTint;
    private TerrainPreviewJob job, save;
    private long jobRevision;
    private TerrainPreviewFrame frame;
    private DynamicTexture texture;
    private int textureId = -1;
    private ProbeGlState drawingState;
    private boolean started, closed, failed, ticking, invalidated;
    private boolean bakeRequested = true, storeTried, wantOverview, shownOverview;
    private int discoveryTicks, ready, pending, missing, visible;
    private String status = "Preparing bounded terrain";
    private String storageWarning;

    TerrainPreviewScreen(WorldClient world) {
        this.originatingWorld = world;
    }

    @Override
    public void initGui() {
        if (started || closed) return;
        started = true; // Resize does not recenter or restart the preview.
        try {
            if (!baseCurrent()) return;
            if (originatingWorld.provider == null || originatingWorld.provider.getClass() != WorldProviderSurface.class
                || originatingWorld.provider.dimensionId != 0
                || originatingWorld.getHeight() != 256
                || mc.thePlayer == null) {
                status = "Unsupported: requires standard Overworld 0..256";
                return;
            }
            double x = mc.thePlayer.posX, y = mc.thePlayer.posY, z = mc.thePlayer.posZ;
            if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)
                || x < -29999984
                || x >= 29999984
                || z < -29999984
                || z >= 29999984
                || y < 0
                || y >= 256) {
                status = "Unsupported: player outside bounded legacy coordinates";
                return;
            }
            key = TerrainPreview.keyFor(x, y, z);
            sunTint = MapStyle.DEFAULT.sunTint()
                .clone();
            shadowTint = MapStyle.DEFAULT.shadowTint()
                .clone();
            session = ClientCaptureLifecycle.openSession(CAPACITY);
            if (session == null) {
                status = "Pending: resource/world epoch not ready; reopen later";
                return;
            }
            store = ClientPreviewStorage.forWorld(mc, originatingWorld);
            if (!current()) return;
            refresh = new TerrainPreviewRefresh(
                ((int) Math.floor(x)) >> 4,
                ((int) Math.floor(z)) >> 4,
                System.nanoTime());
        } catch (RuntimeException error) {
            failed = true;
            stopRefresh();
            status = "Diagnostic unavailable: " + error.getClass()
                .getSimpleName();
            LOG.warn("Diorama terrain preparation failed", error);
        }
    }

    private static TerrainPreviewScreen eventTarget(World world) {
        Minecraft client = Minecraft.getMinecraft();
        if (client == null || !client.func_152345_ab()
            || client.theWorld != world
            || !(client.currentScreen instanceof TerrainPreviewScreen screen)
            || screen.originatingWorld != world
            || screen.refresh == null
            || screen.closed
            || screen.failed) return null;
        return screen;
    }

    public static void blockChanged(World world, int cx, int cz, int localX, int localZ) {
        TerrainPreviewScreen screen = eventTarget(world);
        if (screen == null || screen.refresh.slot(cx, cz) < 0) return;
        long now = System.nanoTime();
        screen.refresh.request(screen.refresh.slot(cx, cz), now, TerrainPreviewRefresh.CHANGE);
        // Actual edits on the border can affect the preview's adjacent shading. This is not
        // a renderer dirty-range or a neighbour update notification.
        int dx = localX == 0 ? -1 : localX == 15 ? 1 : 0;
        int dz = localZ == 0 ? -1 : localZ == 15 ? 1 : 0;
        if (dx != 0) screen.requestRecorded(cx + dx, cz, now, TerrainPreviewRefresh.CHANGE);
        if (dz != 0) screen.requestRecorded(cx, cz + dz, now, TerrainPreviewRefresh.CHANGE);
        if (dx != 0 && dz != 0) screen.requestRecorded(cx + dx, cz + dz, now, TerrainPreviewRefresh.CHANGE);
    }

    public static void chunkFilled(World world, int cx, int cz, boolean full) {
        TerrainPreviewScreen screen = eventTarget(world);
        if (screen == null) return;
        long now = System.nanoTime();
        long delay = full ? TerrainPreviewRefresh.LOAD : TerrainPreviewRefresh.CHANGE;
        screen.refresh.request(screen.refresh.slot(cx, cz), now, delay);
        // Whole/partial packet writes may also replace light; full packets replace the biome
        // columns used by already recorded neighbours to blend their one-block tint halo.
        screen.requestNeighbours(cx, cz, now, delay);
    }

    public static void chunkUnloaded(World world, int cx, int cz) {
        TerrainPreviewScreen screen = eventTarget(world);
        if (screen == null) return;
        long now = System.nanoTime();
        screen.refresh.request(screen.refresh.slot(cx, cz), now, 0);
        screen.requestNeighbours(cx, cz, now, TerrainPreviewRefresh.CHANGE);
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

    private boolean baseCurrent() {
        return !closed && mc != null
            && mc.func_152345_ab()
            && mc.currentScreen == this
            && mc.theWorld == originatingWorld;
    }

    private boolean current() {
        if (invalidated || session == null) return false;
        if (!baseCurrent() || !ClientCaptureLifecycle.accepts(session.token(), session.looks())
            || !baseCurrent()
            || !TerrainPreview.supported()
            || !Arrays.equals(sunTint, MapStyle.DEFAULT.sunTint())
            || !Arrays.equals(shadowTint, MapStyle.DEFAULT.shadowTint())) {
            invalidated = true;
            return false;
        }
        return true;
    }

    private boolean bakeCurrent(long revision) {
        return current() && refresh != null && revision == refresh.revision();
    }

    private void requireCurrent() {
        if (!current()) throw new IllegalStateException("Terrain epoch or screen changed");
    }

    @Override
    public void updateScreen() {
        if (ticking || failed || refresh == null || !baseCurrent()) return;
        ticking = true;
        try {
            if (current()) tickRefresh();
        } catch (RuntimeException error) {
            if (current()) {
                status = "Refresh failed: " + error.getClass()
                    .getSimpleName();
                LOG.warn("Diorama terrain refresh failed", error);
            }
        } finally {
            ticking = false;
        }
    }

    private void tickRefresh() {
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
        for (int budget = TerrainPreviewRefresh.CAPTURES_PER_TICK; budget > 0; budget--) {
            int slot = refresh.takeDue(now, attempted);
            if (slot < 0) break;
            attempted |= 1 << slot;
            captureSlot(slot);
            requireCurrent();
        }
        if (refresh.pending()) {
            status = texture == null ? "Waiting for queued terrain" : "Terrain changed; showing last accepted view";
            return;
        }
        if (!bakeRequested || job != null || save != null || TerrainPreviewJob.isBusy()) return;
        if (ready == 0 && (store == null || storeTried)) {
            bakeRequested = false;
            status = texture == null ? "Pending: no READY chunks; UNKNOWN is not AIR"
                : "No READY chunks; showing last accepted view";
            return;
        }
        long revision = refresh.revision();
        TerrainPreview.Work work = ready > 0 ? TerrainPreview.prepare(session.looks(), session.token(), key, snapshots)
            : null;
        if (!bakeCurrent(revision)) return;
        TerrainPreviewJob startedJob = TerrainPreviewJob.start(key, work, store, frame);
        if (startedJob == null) return;
        job = startedJob;
        jobRevision = revision;
        bakeRequested = false;
        status = work != null ? "Baking bounded detached terrain" : "Checking saved terrain";
    }

    private void captureSlot(int slot) {
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
        if (version != refresh.version(slot)) return; // Reentrant event owns the next attempt.
        snapshots[slot] = snapshot;
        statuses[slot] = outcome;
        bakeRequested = true;
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

    private static void delete(DynamicTexture old, ProbeGlState state) {
        int id = old.getGlTextureId();
        state.forgetDeletedTexture(id);
        old.deleteGlTexture();
    }

    private void release(ProbeGlState state) {
        DynamicTexture old = texture;
        int oldId = textureId;
        texture = null;
        textureId = -1;
        if (old != null) {
            state.forgetDeletedTexture(oldId);
            old.deleteGlTexture();
        }
    }

    private void stopRefresh() {
        refresh = null;
        if (job != null) job.close();
        if (save != null) save.close();
        job = save = null;
        frame = null;
        store = null;
        session = null;
        Arrays.fill(snapshots, null);
    }

    private void stale(ProbeGlState state) {
        failed = true;
        status = "Stale terrain: reopen after the world or resource change";
        stopRefresh();
        release(state);
    }

    private void pollAndUpload(ProbeGlState state) {
        if (save != null && save.done()) {
            if (save.failure() != null) storageWarning = "Save failed: " + save.failure();
            save.close();
            save = null;
        }
        if (job == null || !job.done()) return;
        TerrainPreviewJob finished = job;
        long revision = jobRevision;
        job = null;
        String failure = finished.failure();
        TerrainPreviewFrame candidate = finished.takeFrame();
        finished.close();
        if (!bakeCurrent(revision)) {
            if (current()) bakeRequested = true;
            else stale(state);
            return;
        }
        storeTried = true;
        if (failure != null || candidate == null) {
            status = "Bake failed: " + (failure == null ? "no full image" : failure);
            return;
        }
        storageWarning = candidate.storageFailure();
        boolean overview = wantOverview && candidate.overview() != null || candidate.detail() == null;
        int[] pixels = overview ? candidate.overview() : candidate.detail();
        if (!uploadPixels(pixels, overview, state, () -> bakeCurrent(revision))) {
            if (!current()) stale(state);
            else if (!bakeCurrent(revision)) bakeRequested = true;
            else status = texture == null ? "Pending: no visible terrain"
                : "No replacement terrain; showing last accepted view";
            return;
        }
        if (!current()) {
            stale(state);
            return;
        }
        frame = candidate;
        // A later event may leave this accepted historical frame visible, but must not authorize a
        // superseded candidate to be written. Only send numeric/path-owned data to the worker.
        if (store != null && candidate.needsSave() && bakeCurrent(revision)) {
            save = TerrainPreviewJob.startSave(store, key, candidate);
            if (save == null) storageWarning = "Save worker busy";
        }
        status = "Bounded terrain: refreshes on change";
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) wantOverview = wheel < 0;
    }

    private void updateView(ProbeGlState state) {
        TerrainPreviewFrame accepted = frame;
        if (accepted == null || shownOverview == wantOverview) return;
        int[] pixels = wantOverview ? accepted.overview() : accepted.detail();
        if (pixels != null && !uploadPixels(pixels, wantOverview, state, () -> current() && frame == accepted)
            && !current()) stale(state);
    }

    private boolean uploadPixels(int[] pixels, boolean overview, ProbeGlState state, BooleanSupplier accepts) {
        if (pixels == null) return false;
        int edge = Projection.TILE_PIXELS;
        if (pixels.length != edge * edge) return false;
        int opaque = 0;
        for (int pixel : pixels) if ((pixel >>> 24) != 0) opaque++;
        if (opaque == 0) return false;
        int nextVisible = opaque;
        int[] preparedId = { -1 };
        DynamicTexture previous = texture;
        int previousId = textureId;
        boolean published;
        try {
            published = PreviewUpload.transfer(() -> new DynamicTexture(edge, edge), created -> {
                ProbePixels.copyToArgb(pixels, created.getTextureData());
                if (!accepts.getAsBoolean()) return;
                created.updateDynamicTexture();
                if (!accepts.getAsBoolean()) return;
                preparedId[0] = created.getGlTextureId();
            }, () -> accepts.getAsBoolean() && texture == previous, created -> {
                // Only nonreentrant ownership assignments after the final acceptance check.
                texture = created;
                textureId = preparedId[0];
                visible = nextVisible;
                shownOverview = overview;
            }, created -> delete(created, state));
        } catch (RuntimeException error) {
            LOG.warn("Diorama terrain texture replacement failed", error);
            return false;
        }
        if (published && previous != null) {
            state.forgetDeletedTexture(previousId);
            previous.deleteGlTexture();
        }
        return published;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (closed || drawingState != null) return;
        ProbeGlState state = new ProbeGlState();
        drawingState = state;
        try {
            try {
                if (!failed && session != null && !current()) stale(state);
                if (!failed) {
                    pollAndUpload(state);
                    updateView(state);
                }
                if (!baseCurrent()) return;
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                drawRect(0, 0, width, height, 0xF0202020);
                if (texture != null) {
                    if (!current()) {
                        stale(state);
                        return;
                    }
                    DynamicTexture shown = texture;
                    // Never call an allocating getter after transferring texture ownership: a
                    // getter hook could close/delete and then resurrect the local texture.
                    int id = textureId;
                    if (!current() || texture != shown) {
                        stale(state);
                        return;
                    }
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glEnable(GL11.GL_BLEND);
                    OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                    GL11.glColor4f(1, 1, 1, 1);
                    if (!current() || texture != shown) {
                        stale(state);
                        return;
                    }
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                    if (!current() || texture != shown) {
                        stale(state);
                        return;
                    }
                    int edge = Math.max(1, Math.min(384, Math.min(width - 24, height - 102)));
                    int left = (width - edge) / 2, top = 32;
                    // Own the CPU batch so a revoked pre-draw batch can be discarded without
                    // flushing stale vertices or leaving Minecraft's singleton in drawing mode.
                    Tessellator tessellator = new Tessellator();
                    tessellator.startDrawingQuads();
                    tessellator.addVertexWithUV(left, top + edge, 0, 0, 1);
                    tessellator.addVertexWithUV(left + edge, top + edge, 0, 1, 1);
                    tessellator.addVertexWithUV(left + edge, top, 0, 1, 0);
                    tessellator.addVertexWithUV(left, top, 0, 0, 0);
                    if (!current() || texture != shown) {
                        stale(state);
                        return;
                    }
                    tessellator.draw();
                    if (!current() || texture != shown) {
                        stale(state);
                        return;
                    }
                }
                if (!baseCurrent()) return;
                drawCenteredString(fontRendererObj, "Diorama terrain preview", width / 2, 10, 0xFFFFFF);
                drawCenteredString(
                    fontRendererObj,
                    storageWarning == null ? status : status + " (" + storageWarning + ")",
                    width / 2,
                    height - 62,
                    0xFFFF80);
                drawCenteredString(
                    fontRendererObj,
                    "READY " + ready
                        + "/9; pending "
                        + pending
                        + "; missing "
                        + missing
                        + "; visible pixels "
                        + visible,
                    width / 2,
                    height - 48,
                    0xFFFFFF);
                drawCenteredString(
                    fontRendererObj,
                    (texture == null ? "" : shownOverview ? "L1 overview. " : "L0 detail. ")
                        + "Bounded 3x3; wheel: zoom; Esc: close",
                    width / 2,
                    height - 34,
                    0xFFAA80);
            } catch (RuntimeException error) {
                failed = true;
                status = "Diagnostic draw failed; close and rerun";
                stopRefresh();
                release(state);
                LOG.warn("Diorama terrain draw failed", error);
            }
        } finally {
            try {
                state.close();
            } finally {
                drawingState = null;
            }
        }
    }

    @Override
    public void onGuiClosed() {
        closed = true;
        stopRefresh();
        if (drawingState != null) release(drawingState);
        else try (ProbeGlState state = new ProbeGlState()) {
            release(state);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
