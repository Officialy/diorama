package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.TerrainMetrics;
import dev.diorama.gtnh.capture.TerrainPreview;
import dev.diorama.gtnh.diagnostic.ProbePixels;

/** Legacy HUD owner. CPU-baked tiles are reused across frames; GL work is limited to uploads and drawing. */
public final class ClientMinimap {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final int CACHE_TILES = 8;
    private static final int CAPTURES_PER_TICK = 2;
    private static boolean enabled = true;
    private static @Nullable ClientMinimap installed;

    private final Map<TileKey, CachedTile> tiles = new LinkedHashMap<>(CACHE_TILES + 1, 0.75f, true);
    private @Nullable WorldClient world;
    private ClientCaptureLifecycle.@Nullable Session session;
    private @Nullable TerrainWindow window;
    private float[] sunTint, shadowTint;
    private int centerX, centerZ;
    private long captureGeneration, textureGeneration;
    private @Nullable TerrainPreviewJob job;
    private @Nullable TileKey jobKey;
    private long jobRevision, jobGeneration;
    private @Nullable ProbeGlState drawing;
    private boolean updating;
    private int retryTicks;

    ClientMinimap() {
        installed = this;
    }

    static void toggle() {
        enabled = !enabled;
        if (!enabled && installed != null) installed.reset();
    }

    static void tick(Minecraft mc) {
        if (installed != null) installed.update(mc);
        if (TerrainMetrics.ENABLED) {
            String report = TerrainMetrics.reportIfDue();
            if (report != null) LOG.info("Diorama terrain metrics {}", report);
        }
    }

    private static @Nullable TerrainWindow eventWindow(World source) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientMinimap hud = installed;
        return hud != null && hud.world == source && mc.theWorld == source && mc.func_152345_ab() ? hud.window : null;
    }

    static void blockChanged(World world, int cx, int cz, int x, int z) {
        TerrainWindow input = eventWindow(world);
        if (input != null) input.blockChanged(cx, cz, x, z);
    }

    static void chunkFilled(World world, int cx, int cz, boolean full) {
        TerrainWindow input = eventWindow(world);
        if (input != null) input.chunkFilled(cx, cz, full);
    }

    static void chunkUnloaded(World world, int cx, int cz) {
        TerrainWindow input = eventWindow(world);
        if (input != null) input.chunkUnloaded(cx, cz);
    }

    private static boolean canShow(Minecraft mc) {
        return enabled && !mc.gameSettings.hideGUI
            && !mc.gameSettings.showDebugInfo
            && (mc.currentScreen == null || mc.currentScreen instanceof GuiChat);
    }

    private static boolean surface(WorldClient world) {
        return world.provider != null && world.provider.getClass() == WorldProviderSurface.class
            && world.provider.dimensionId == 0
            && world.getHeight() == 256;
    }

    private static boolean bounded(double x, double y, double z) {
        return x >= -29999984 && x < 29999984 && z >= -29999984 && z < 29999984 && y >= 0 && y < 256;
    }

    private boolean current(Minecraft mc) {
        ClientCaptureLifecycle.Session opened = session;
        return opened != null && enabled
            && world == mc.theWorld
            && TerrainPreview.supported()
            && Arrays.equals(sunTint, MapStyle.DEFAULT.sunTint())
            && Arrays.equals(shadowTint, MapStyle.DEFAULT.shadowTint())
            && ClientCaptureLifecycle.accepts(opened.token(), opened.looks())
            && session == opened
            && world == mc.theWorld;
    }

    private void update(Minecraft mc) {
        if (updating) return;
        updating = true;
        try {
            if (world != null && !current(mc)) reset();
            if (retryTicks > 0) {
                retryTicks--;
                return;
            }
            if (!canShow(mc)) {
                // Let the already running job finish or discard it; never schedule under another GUI.
                if (mc.currentScreen instanceof TerrainPreviewScreen) discardJob();
                return;
            }
            WorldClient active = mc.theWorld;
            EntityClientPlayerMP player = mc.thePlayer;
            if (active == null || player == null
                || !surface(active)
                || !bounded(player.posX, player.boundingBox.minY, player.posZ)
                || !TerrainPreview.supported()) return;
            if (session == null) {
                ClientCaptureLifecycle.Session opened = ClientCaptureLifecycle.openSession(TerrainWindow.CAPACITY);
                if (opened == null || active != mc.theWorld) return;
                world = active;
                session = opened;
                sunTint = MapStyle.DEFAULT.sunTint()
                    .clone();
                shadowTint = MapStyle.DEFAULT.shadowTint()
                    .clone();
            }
            if (!current(mc)) return;
            int cx = MathHelper.floor_double(player.posX) >> 4, cz = MathHelper.floor_double(player.posZ) >> 4;
            if (window == null || cx != centerX || cz != centerZ) {
                discardJob();
                centerX = cx;
                centerZ = cz;
                window = new TerrainWindow(session, cx, cz, () -> current(mc), window);
                captureGeneration++;
            }
            if (window.tick(CAPTURES_PER_TICK)) captureGeneration++;
            if (!current(mc)) return;
            finish(mc);
            if (retryTicks > 0 || job != null || TerrainPreviewJob.isBusy() || window.queued() || window.ready() == 0)
                return;
            MinimapViewport view = view(player.posX, player.boundingBox.minY, player.posZ);
            // Fill a newly exposed tile first, then replace older coverage. Only one worker slot.
            TileKey selected = choose(view);
            if (selected == null) return;
            CachedTile prior = tiles.get(selected);
            long revision = window.revision();
            TerrainPreview.Work work = window.prepare(selected);
            if (!current(mc) || window.revision() != revision) return;
            job = TerrainPreviewJob.startDetail(work, prior == null ? null : prior.pixels);
            if (job != null) {
                jobKey = selected;
                jobGeneration = captureGeneration;
                jobRevision = revision;
            }
        } catch (RuntimeException failure) {
            backoff(failure);
        } finally {
            updating = false;
        }
    }

    private @Nullable TileKey choose(MinimapViewport view) {
        TileKey stale = null;
        for (MinimapViewport.Tile part : view.tiles()) {
            CachedTile entry = tiles.get(part.key());
            if (entry == null) return part.key();
            if (stale == null && entry.generation != captureGeneration) stale = part.key();
        }
        return stale;
    }

    private void finish(Minecraft mc) {
        TerrainPreviewJob finished = job;
        TileKey key = jobKey;
        if (finished == null || key == null || !finished.done()) return;
        TerrainPreviewFrame frame = finished.takeFrame();
        String failure = finished.failure();
        discardJob();
        if (!current(mc) || window == null || jobGeneration != captureGeneration || jobRevision != window.revision())
            return;
        if (failure != null || frame == null) {
            retryTicks = 100;
            LOG.warn("Diorama minimap tile bake failed: {}", failure == null ? "missing result" : failure);
            return;
        }
        CachedTile entry = tiles.get(key);
        if (entry == null) {
            if (tiles.size() == CACHE_TILES) evict();
            if (!current(mc)) return;
            entry = new CachedTile();
            tiles.put(key, entry);
        }
        entry.generation = captureGeneration;
        if (frame.detail() != null) entry.pixels = frame.detail();
    }

    private void discardJob() {
        if (job != null) job.close();
        job = null;
        jobKey = null;
    }

    private void evict() {
        var entries = tiles.entrySet()
            .iterator();
        CachedTile oldest = entries.next()
            .getValue();
        entries.remove();
        textureGeneration++;
        if (oldest.texture != null) try (ProbeGlState state = new ProbeGlState()) {
            oldest.release(state);
        }
    }

    private void reset() {
        discardJob();
        window = null;
        session = null;
        world = null;
        List<CachedTile> released = List.copyOf(tiles.values());
        tiles.clear();
        textureGeneration++;
        for (CachedTile entry : released) {
            if (entry.texture == null) continue;
            if (drawing != null) entry.release(drawing);
            else try (ProbeGlState state = new ProbeGlState()) {
                entry.release(state);
            }
        }
    }

    private void backoff(RuntimeException failure) {
        retryTicks = 100;
        LOG.warn("Diorama minimap temporarily unavailable", failure);
        reset();
    }

    private static MinimapViewport view(double x, double y, double z) {
        return new MinimapViewport(Projection.u(x, z), Projection.v(x, y, z));
    }

    private static double lerp(double old, double now, float fraction) {
        return old + (now - old) * fraction;
    }

    @SubscribeEvent
    public void draw(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || drawing != null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!canShow(mc) || !current(mc)) return;
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) return;
        float delta = event.partialTicks;
        double x = lerp(player.lastTickPosX, player.posX, delta),
            y = player.boundingBox.minY + (player.lastTickPosY - player.posY) * (1 - delta),
            z = lerp(player.lastTickPosZ, player.posZ, delta);
        if (!bounded(x, y, z)) return;
        MinimapViewport view = view(x, y, z);
        ClientCaptureLifecycle.Session shown = session;
        try (ProbeGlState state = new ProbeGlState()) {
            drawing = state;
            for (MinimapViewport.Tile part : view.tiles()) {
                CachedTile tile = tiles.get(part.key());
                if (tile != null && tile.pixels != null && tile.pixels != tile.uploaded) {
                    upload(mc, part.key(), tile, state);
                    break; // Never queue multiple full texture uploads on one render frame.
                }
            }
            float yaw = player.prevRotationYaw
                + MathHelper.wrapAngleTo180_float(player.rotationYaw - player.prevRotationYaw) * delta;
            long shownTextures = textureGeneration;
            LegacyMinimapDraw
                .draw(mc, event.resolution.getScaledWidth(), event.resolution.getScaledHeight(), view, key -> {
                    CachedTile tile = tiles.get(key);
                    return tile == null ? -1 : tile.id;
                },
                    yaw,
                    MathHelper.floor_double(x) + ", " + MathHelper.floor_double(y) + ", " + MathHelper.floor_double(z),
                    () -> current(mc) && canShow(mc) && session == shown && textureGeneration == shownTextures);
        } catch (RuntimeException failure) {
            drawing = null; // The try-with-resources state has already been restored.
            backoff(failure);
        } finally {
            drawing = null;
        }
    }

    private void upload(Minecraft mc, TileKey key, CachedTile tile, ProbeGlState state) {
        int[] scratch = tile.pixels;
        DynamicTexture old = tile.texture;
        int oldId = tile.id;
        int edge = Projection.TILE_PIXELS;
        int[] raw = { -1 };
        long started = TerrainMetrics.ENABLED ? System.nanoTime() : 0;
        boolean published = false, failed = true;
        try {
            published = PreviewUpload.transfer(() -> new DynamicTexture(edge, edge), texture -> {
                raw[0] = texture.getGlTextureId();
                ProbePixels.copyToArgb(scratch, texture.getTextureData());
                if (current(mc) && tiles.get(key) == tile && tile.pixels == scratch) texture.updateDynamicTexture();
            },
                () -> current(mc) && tiles.get(key) == tile && tile.pixels == scratch && tile.texture == old,
                texture -> {
                    tile.texture = texture;
                    tile.id = raw[0];
                    tile.uploaded = scratch;
                    textureGeneration++;
                },
                texture -> {
                    if (raw[0] >= 0) state.forgetDeletedTexture(raw[0]);
                    texture.deleteGlTexture();
                });
            failed = false;
        } finally {
            if (TerrainMetrics.ENABLED) {
                TerrainMetrics.record(TerrainMetrics.Phase.HUD_UPLOAD, System.nanoTime() - started);
                TerrainMetrics.add(
                    failed ? TerrainMetrics.Counter.HUD_UPLOAD_FAILED
                        : published ? TerrainMetrics.Counter.HUD_UPLOAD_PUBLISHED
                            : TerrainMetrics.Counter.HUD_UPLOAD_REJECTED,
                    1);
            }
        }
        if (published && old != null) {
            state.forgetDeletedTexture(oldId);
            old.deleteGlTexture();
        }
    }

    private static final class CachedTile {

        private long generation;
        private int[] pixels, uploaded;
        private DynamicTexture texture;
        private int id = -1;

        private void release(ProbeGlState state) {
            DynamicTexture old = texture;
            int raw = id;
            id = -1;
            texture = null;
            uploaded = null;
            state.forgetDeletedTexture(raw);
            old.deleteGlTexture();
        }
    }
}
