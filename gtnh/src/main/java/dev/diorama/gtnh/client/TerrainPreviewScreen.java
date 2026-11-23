package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewTileStore;
import dev.diorama.gtnh.capture.TerrainMetrics;
import dev.diorama.gtnh.capture.TerrainPreview;
import dev.diorama.gtnh.diagnostic.ProbePixels;

/** Event-driven preview, fixed to the opening tile and nine chunk coordinates. */
public final class TerrainPreviewScreen extends GuiScreen {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private final WorldClient originatingWorld;
    private ClientCaptureLifecycle.Session session;
    private TerrainWindow window;
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
    private int visible;
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
            double x = mc.thePlayer.posX, y = mc.thePlayer.boundingBox.minY, z = mc.thePlayer.posZ;
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
            session = ClientCaptureLifecycle.openSession(TerrainWindow.CAPACITY);
            if (session == null) {
                status = "Pending: resource/world epoch not ready; reopen later";
                return;
            }
            store = ClientPreviewStorage.forWorld(mc, originatingWorld);
            if (!current()) return;
            window = new TerrainWindow(
                session,
                ((int) Math.floor(x)) >> 4,
                ((int) Math.floor(z)) >> 4,
                this::current,
                null);
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
            || screen.window == null
            || screen.closed
            || screen.failed) return null;
        return screen;
    }

    public static void blockChanged(World world, int cx, int cz, int localX, int y, int localZ) {
        TerrainPreviewScreen screen = eventTarget(world);
        if (screen != null) screen.window.blockChanged(cx, cz, localX, localZ);
        ClientMinimap.blockChanged(world, cx, cz, localX, y, localZ);
    }

    public static void chunkFilled(World world, int cx, int cz, boolean full) {
        TerrainPreviewScreen screen = eventTarget(world);
        if (screen != null) screen.window.chunkFilled(cx, cz, full);
        ClientMinimap.chunkFilled(world, cx, cz, full);
    }

    public static void chunkUnloaded(World world, int cx, int cz) {
        TerrainPreviewScreen screen = eventTarget(world);
        if (screen != null) screen.window.chunkUnloaded(cx, cz);
        ClientMinimap.chunkUnloaded(world, cx, cz);
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
        return current() && window != null && revision == window.revision();
    }

    @Override
    public void updateScreen() {
        if (ticking || failed || window == null || !baseCurrent()) return;
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
        bakeRequested |= window.tick(TerrainPreviewRefresh.CAPTURES_PER_TICK);
        if (window.queued()) {
            status = texture == null ? "Waiting for queued terrain" : "Terrain changed; showing last accepted view";
            return;
        }
        if (!bakeRequested || job != null || save != null || TerrainPreviewJob.isBusy()) return;
        if (window.ready() == 0 && (store == null || storeTried)) {
            bakeRequested = false;
            status = texture == null ? "Pending: no READY chunks; UNKNOWN is not AIR"
                : "No READY chunks; showing last accepted view";
            return;
        }
        long revision = window.revision();
        TerrainPreview.Work work = window.ready() > 0 ? window.prepare(key) : null;
        if (!bakeCurrent(revision)) return;
        TerrainPreviewJob startedJob = TerrainPreviewJob.start(key, work, store, frame);
        if (startedJob == null) return;
        job = startedJob;
        jobRevision = revision;
        bakeRequested = false;
        status = work != null ? "Baking bounded detached terrain" : "Checking saved terrain";
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
        window = null;
        if (job != null) job.close();
        if (save != null) save.close();
        job = save = null;
        frame = null;
        store = null;
        session = null;
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
    protected void keyTyped(char character, int keyCode) {
        if (ClientProxy.isMapKey(keyCode)) {
            if (!Keyboard.isRepeatEvent()) mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(character, keyCode);
    }

    @Override
    public void handleMouseInput() {
        if (Mouse.getEventButtonState() && ClientProxy.isMapMouseButton(Mouse.getEventButton())) {
            mc.displayGuiScreen(null);
            return;
        }
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
        long started = TerrainMetrics.ENABLED ? System.nanoTime() : 0;
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
            if (TerrainMetrics.ENABLED) {
                TerrainMetrics.record(TerrainMetrics.Phase.MAP_UPLOAD, System.nanoTime() - started);
                TerrainMetrics.add(TerrainMetrics.Counter.MAP_UPLOAD_FAILED, 1);
            }
            LOG.warn("Diorama terrain texture replacement failed", error);
            return false;
        }
        if (TerrainMetrics.ENABLED) {
            TerrainMetrics.record(TerrainMetrics.Phase.MAP_UPLOAD, System.nanoTime() - started);
            TerrainMetrics.add(
                published ? TerrainMetrics.Counter.MAP_UPLOAD_PUBLISHED : TerrainMetrics.Counter.MAP_UPLOAD_REJECTED,
                1);
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
                    "READY " + (window == null ? 0 : window.ready())
                        + "/9; pending "
                        + (window == null ? 0 : window.pendingCount())
                        + "; missing "
                        + (window == null ? 0 : window.missing())
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
