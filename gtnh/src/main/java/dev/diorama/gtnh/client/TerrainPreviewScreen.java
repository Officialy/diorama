package dev.diorama.gtnh.client;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockGrass;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldProviderSurface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import dev.diorama.Projection;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.TerrainPreview;
import dev.diorama.gtnh.diagnostic.ProbePixels;

/** Explicit bounded L0 diagnostic; no tick capture, updates, persistence, HUD or automatic retry. */
public final class TerrainPreviewScreen extends GuiScreen {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final int CAPACITY = 4096;
    private final WorldClient originatingWorld;
    private ClientCaptureLifecycle.Session session;
    private TerrainPreview.Work work;
    private TerrainPreviewJob job;
    private DynamicTexture texture;
    private int textureId = -1;
    private ProbeGlState drawingState;
    private boolean started, closed, failed;
    private int ready, pending, missing, visible;
    private String status = "Preparing one-shot terrain diagnostic";

    TerrainPreviewScreen(WorldClient world) {
        this.originatingWorld = world;
    }

    @Override
    public void initGui() {
        if (started || closed) return;
        started = true; // Resize must never recapture or enqueue another job.
        try {
            if (!baseCurrent()) return;
            if (TerrainPreviewJob.isBusy()) {
                status = "Busy: previous bake still running; close and rerun later";
                return;
            }
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
            TileKey key = TerrainPreview.keyFor(x, y, z);
            session = ClientCaptureLifecycle.openSession(CAPACITY);
            if (session == null) {
                status = "Pending: resource/world epoch not ready; rerun later";
                return;
            }
            int cx = ((int) Math.floor(x)) >> 4, cz = ((int) Math.floor(z)) >> 4;
            ChunkSnapshot[] snapshots = new ChunkSnapshot[9];
            int slot = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                requireCurrent();
                int chunkX = cx + dx, chunkZ = cz + dz;
                ChunkCapture.Chunk chunk = session.access()
                    .loaded(chunkX, chunkZ);
                requireCurrent();
                if (chunk == null) {
                    missing++;
                    slot++;
                    continue;
                }
                if (!preflight(chunk)) {
                    pending++;
                    slot++;
                    continue;
                }
                ChunkCapture.Result result = session.capture(chunkX, chunkZ);
                requireCurrent();
                if (result.status() == ChunkCapture.Status.READY && ClientCaptureLifecycle.accepts(result)) {
                    requireCurrent();
                    snapshots[slot] = result.snapshot();
                    ready++;
                } else if (result.status() == ChunkCapture.Status.MISSING_CHUNK) missing++;
                else pending++;
                slot++;
            }
            requireCurrent();
            if (ready == 0) {
                status = "Pending: 0 READY chunks; unsupported states (e.g. snow) are not AIR";
                return;
            }
            work = TerrainPreview.prepare(session.looks(), session.token(), key, snapshots);
            requireCurrent();
            job = TerrainPreviewJob.start(work); // Only detached Work crosses the worker boundary.
            status = job == null ? "Busy: previous bake still running; rerun later" : "Baking bounded detached terrain";
        } catch (RuntimeException error) {
            failed = true;
            status = "Diagnostic unavailable: " + error.getClass()
                .getSimpleName() + "; close and rerun";
            LOG.warn("Diorama terrain preparation failed", error);
        }
    }

    private boolean baseCurrent() {
        return !closed && mc != null
            && mc.func_152345_ab()
            && mc.currentScreen == this
            && mc.theWorld == originatingWorld;
    }

    private boolean current() {
        if (!baseCurrent() || session == null) return false;
        if (!ClientCaptureLifecycle.accepts(session.token(), session.looks())) return false;
        return baseCurrent() && (work == null || work.stillCurrent());
    }

    private void requireCurrent() {
        if (!current()) throw new IllegalStateException("Terrain epoch or screen changed");
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
        texture = null; // Detach ownership before any potentially reentrant GL callback.
        textureId = -1;
        if (old != null) {
            state.forgetDeletedTexture(oldId);
            old.deleteGlTexture();
        }
    }

    private void stale(ProbeGlState state) {
        failed = true;
        status = "Stale terrain: close and rerun /dioramaterrain (no automatic rebuild)";
        if (job != null) {
            job.close();
            job = null;
        }
        work = null;
        session = null;
        release(state);
    }

    private void pollAndUpload(ProbeGlState state) {
        if (job == null || !job.done()) return;
        if (!current()) {
            stale(state);
            return;
        }
        TerrainPreviewJob finished = job;
        job = null;
        String failure = finished.failure();
        TileImage image = finished.takeImage();
        finished.close();
        if (failure != null || image == null || !image.isFull() || image.mips() == null) {
            failed = true;
            status = "Bake failed: " + (failure == null ? "no full image" : failure);
            return;
        }
        if (!current()) {
            stale(state);
            return;
        }
        int[] pixels = image.mips()[0];
        if (pixels == null || pixels.length != Projection.TILE_PIXELS * Projection.TILE_PIXELS)
            throw new IllegalStateException("Unexpected terrain image dimensions");
        visible = 0;
        for (int pixel : pixels) if ((pixel >>> 24) != 0) visible++;
        if (texture != null) throw new IllegalStateException("Terrain texture already published");
        int[] preparedId = { -1 };
        boolean published = PreviewUpload
            .transfer(() -> new DynamicTexture(Projection.TILE_PIXELS, Projection.TILE_PIXELS), created -> {
                ProbePixels.copyToArgb(pixels, created.getTextureData());
                if (!current()) return;
                created.updateDynamicTexture();
                if (!current()) return;
                preparedId[0] = created.getGlTextureId();
            }, this::current, created -> {
                // Only nonreentrant ownership assignments after the final acceptance check.
                texture = created;
                textureId = preparedId[0];
            }, created -> delete(created, state));
        if (!published || !current()) {
            stale(state);
            return;
        }
        status = visible == 0 ? "Baked, but 0 visible pixels in this bounded tile"
            : "One-shot terrain (not a live map)";
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (closed) return;
        ProbeGlState state = new ProbeGlState();
        drawingState = state;
        try {
            try {
                if (!failed && session != null && !current()) stale(state);
                if (!failed) pollAndUpload(state);
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
                drawCenteredString(fontRendererObj, "Diorama terrain DEVELOPMENT diagnostic", width / 2, 10, 0xFFFFFF);
                drawCenteredString(fontRendererObj, status, width / 2, height - 62, 0xFFFF80);
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
                    "Bounded 3x3; UNKNOWN outside; partial geometry/lighting. Esc: close",
                    width / 2,
                    height - 34,
                    0xFFAA80);
            } catch (RuntimeException error) {
                failed = true;
                status = "Diagnostic draw failed; close and rerun";
                if (job != null) {
                    job.close();
                    job = null;
                }
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
        if (job != null) {
            job.close();
            job = null;
        }
        work = null;
        session = null;
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
