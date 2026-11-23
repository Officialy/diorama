package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.TerrainMetrics;
import dev.diorama.gtnh.capture.TerrainPreview;
import dev.diorama.gtnh.diagnostic.ProbePixels;

/** Shared bounded terrain session; fullscreen map and HUD consume the same resident raster and texture. */
public final class ClientMinimap {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final int CACHE_TILES = 8;
    private static final int PATCH_FALLBACK = 160;
    private static final int PATCH_SIDE = Projection.TILE_PIXELS / TileImage.BLOCK;
    private static final int CAPTURES_PER_TICK = 2;
    private static boolean enabled = true;
    private static @Nullable ClientMinimap installed;

    private final Map<TileKey, CachedTile> tiles = new LinkedHashMap<>(CACHE_TILES + 1, 0.75f, true);
    private @Nullable WorldClient world;
    private ClientCaptureLifecycle.@Nullable Session session;
    private @Nullable TerrainWindow window;
    private float[] sunTint, shadowTint;
    private int centerX, centerZ;
    private long textureGeneration;
    private @Nullable TerrainPreviewJob job;
    private @Nullable TileKey jobKey;
    private long jobRevision;
    private int jobTop;
    private @Nullable BitSet jobPatches;
    private @Nullable ProbeGlState drawing;
    private boolean updating;
    private int retryTicks;

    ClientMinimap() {
        installed = this;
    }

    static void toggle() {
        enabled = !enabled;
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

    static void blockChanged(World world, int cx, int cz, int x, int y, int z) {
        TerrainWindow input = eventWindow(world);
        if (input == null || !input.contains(cx, cz)) return;
        input.blockChanged(cx, cz, x, z);
        for (var entry : installed.tiles.entrySet()) {
            entry.getValue()
                .invalidate(DirtyTerrainTiles.block(entry.getKey(), cx * 16 + x, y, cz * 16 + z));
        }
    }

    static void chunkFilled(World world, int cx, int cz, boolean full) {
        TerrainWindow input = eventWindow(world);
        if (input == null) return;
        input.chunkFilled(cx, cz, full);
        if (input.near(cx, cz)) installed.dirtyHalo(cx, cz);
    }

    static void chunkUnloaded(World world, int cx, int cz) {
        TerrainWindow input = eventWindow(world);
        if (input == null) return;
        input.chunkUnloaded(cx, cz);
        if (input.near(cx, cz)) installed.dirtyHalo(cx, cz);
    }

    private void dirtyHalo(int cx, int cz) {
        // Fill and unload may affect adjacent biome samples and light. Unlike a single block
        // update, neither supplies a safe height bound.
        for (var entry : tiles.entrySet()) {
            entry.getValue()
                .invalidate(
                    DirtyTerrainTiles
                        .range(entry.getKey(), cx * 16 - 16, 0, cz * 16 - 16, cx * 16 + 31, 255, cz * 16 + 31));
        }
    }

    private void dirtyChunk(int cx, int cz) {
        for (var entry : tiles.entrySet()) entry.getValue()
            .invalidate(DirtyTerrainTiles.chunk(entry.getKey(), cx, cz));
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
        return opened != null && world == mc.theWorld
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
            SharedTerrainMapScreen map = mc.currentScreen instanceof SharedTerrainMapScreen screen
                && screen.belongsTo(mc.theWorld) ? screen : null;
            if (map == null && !canShow(mc)) {
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
                if (window != null) {
                    for (int z = centerZ - 1; z <= centerZ + 1; z++) for (int x = centerX - 1; x <= centerX + 1; x++)
                        if (Math.abs(x - cx) > 1 || Math.abs(z - cz) > 1) dirtyChunk(x, z);
                    for (int z = cz - 1; z <= cz + 1; z++)
                        for (int x = cx - 1; x <= cx + 1; x++) if (!window.contains(x, z)) dirtyChunk(x, z);
                }
                centerX = cx;
                centerZ = cz;
                window = new TerrainWindow(session, cx, cz, () -> current(mc), window, this::dirtyChunk);
            }
            window.tick(CAPTURES_PER_TICK);
            if (!current(mc)) return;
            finish(mc);
            if (retryTicks > 0 || job != null || TerrainPreviewJob.isBusy() || window.queued() || window.ready() == 0)
                return;
            List<TileKey> wanted = map == null ? view(player.posX, player.boundingBox.minY, player.posZ).tiles()
                .stream()
                .map(MinimapViewport.Tile::key)
                .collect(java.util.stream.Collectors.toList()) : mapWanted(map.viewport());
            TileKey selected = choose(wanted, window.topY());
            if (selected == null) return;
            CachedTile entry = tiles.get(selected);
            if (entry == null) {
                if (tiles.size() == CACHE_TILES) evict();
                if (!current(mc)) return;
                entry = new CachedTile();
                tiles.put(selected, entry);
            }
            long revision = entry.revision;
            TerrainPreview.Work work = window.prepare(selected);
            if (!current(mc) || entry.revision != revision) return;
            BitSet patches = entry.pixels != null && !entry.needsFull
                && entry.top == work.topY()
                && entry.dirty.cardinality() <= PATCH_FALLBACK ? (BitSet) entry.dirty.clone() : null;
            job = patches == null ? TerrainPreviewJob.startDetail(work, entry.pixels)
                : TerrainPreviewJob.startDetailPatches(work, entry.pixels, patches);
            if (job != null) {
                jobKey = selected;
                jobRevision = revision;
                jobTop = work.topY();
                jobPatches = patches;
            }
        } catch (RuntimeException failure) {
            backoff(failure);
        } finally {
            updating = false;
        }
    }

    private @Nullable TileKey choose(List<TileKey> wanted, int top) {
        TileKey stale = null;
        for (TileKey key : wanted) {
            CachedTile entry = tiles.get(key);
            if (entry == null) return key;
            if (stale == null && (entry.needsFull || entry.top != top || !entry.dirty.isEmpty())) stale = key;
        }
        return stale;
    }

    private void finish(Minecraft mc) {
        TerrainPreviewJob finished = job;
        TileKey key = jobKey;
        if (finished == null || key == null || !finished.done()) return;
        TerrainPreviewFrame frame = finished.takeFrame();
        String failure = finished.failure();
        BitSet patches = jobPatches;
        discardJob();
        if (!current(mc)) return;
        CachedTile entry = tiles.get(key);
        if (entry == null || jobRevision != entry.revision || window.topY() != jobTop) return;
        if (failure != null || frame == null) {
            retryTicks = 100;
            entry.needsFull = true;
            LOG.warn("Diorama minimap tile bake failed: {}", failure == null ? "missing result" : failure);
            return;
        }
        entry.dirty.clear();
        entry.needsFull = false;
        entry.top = jobTop;
        if (frame.detail() != null && frame.detail() != entry.pixels) {
            entry.pixels = frame.detail();
            if (patches == null) entry.pendingUpload = null;
            else if (entry.pendingUpload != null) entry.pendingUpload.or(patches);
        }
    }

    private void discardJob() {
        if (job != null) job.close();
        job = null;
        jobKey = null;
        jobPatches = null;
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
                if (tile != null && tile.pixels != null
                    && (tile.pixels != tile.uploaded || tile.anaglyph != mc.gameSettings.anaglyph)) {
                    if (!uploadPatch(mc, part.key(), tile, state)) upload(mc, part.key(), tile, state);
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

    private List<TileKey> mapWanted(MapViewportController view) {
        // Ranking is stable for a view. Never rotate visible misses through a smaller cache.
        // Remote panning reads explored resident tiles, but cannot cause new world/chunk loads.
        return view.tiles()
            .stream()
            .map(MapViewportController.Tile::key)
            .filter(
                key -> !DirtyTerrainTiles
                    .range(
                        key,
                        (centerX - 1) * 16,
                        0,
                        (centerZ - 1) * 16,
                        (centerX + 2) * 16 - 1,
                        255,
                        (centerZ + 2) * 16 - 1)
                    .isEmpty())
            .sorted(
                Comparator.comparingDouble(
                    key -> Math.hypot(
                        key.minU() + Projection.TILE_UNITS * .5 - view.centerU(),
                        key.minV() + Projection.TILE_UNITS * .5 - view.centerV())))
            .limit(CACHE_TILES)
            .collect(java.util.stream.Collectors.toList());
    }

    static void drawMap(Minecraft mc, SharedTerrainMapScreen screen) {
        ClientMinimap owner = installed;
        if (owner != null) owner.drawSharedMap(mc, screen);
    }

    private void drawSharedMap(Minecraft mc, SharedTerrainMapScreen screen) {
        if (drawing != null || !screen.belongsTo(mc.theWorld) || !current(mc)) return;
        ClientCaptureLifecycle.Session shown = session;
        MapViewportController view = screen.viewport();
        try (ProbeGlState state = new ProbeGlState()) {
            drawing = state;
            for (TileKey key : mapWanted(view)) {
                CachedTile tile = tiles.get(key);
                if (tile != null && tile.pixels != null
                    && (tile.pixels != tile.uploaded || tile.anaglyph != mc.gameSettings.anaglyph)) {
                    if (!uploadPatch(mc, key, tile, state)) upload(mc, key, tile, state);
                    break;
                }
            }
            var keys = List.copyOf(tiles.keySet());
            long shownTextures = textureGeneration;
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL11.glColor4f(1, 1, 1, 1);
            Tessellator mesh = null;
            for (TileKey key : keys) {
                MapViewportController.Tile part = view.clip(key);
                if (part == null) continue;
                CachedTile tile = tiles.get(key);
                if (tile == null || tile.id <= 0) continue;
                if (!mapCurrent(mc, screen, shown, shownTextures)) return;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.id);
                if (!mapCurrent(mc, screen, shown, shownTextures)) return;
                if (mesh == null) mesh = new Tessellator();
                mesh.startDrawingQuads();
                mesh.addVertexWithUV(part.x0(), part.y1(), 0, part.u0(), part.v1());
                mesh.addVertexWithUV(part.x1(), part.y1(), 0, part.u1(), part.v1());
                mesh.addVertexWithUV(part.x1(), part.y0(), 0, part.u1(), part.v0());
                mesh.addVertexWithUV(part.x0(), part.y0(), 0, part.u0(), part.v0());
                if (!mapCurrent(mc, screen, shown, shownTextures)) return;
                mesh.draw();
            }
        } catch (RuntimeException failure) {
            drawing = null;
            backoff(failure);
        } finally {
            drawing = null;
        }
    }

    private boolean mapCurrent(Minecraft mc, SharedTerrainMapScreen screen, ClientCaptureLifecycle.Session shown,
        long shownTextures) {
        return current(mc) && mc.currentScreen == screen
            && screen.belongsTo(mc.theWorld)
            && session == shown
            && textureGeneration == shownTextures;
    }

    private boolean uploadPatch(Minecraft mc, TileKey key, CachedTile tile, ProbeGlState state) {
        BitSet mask = tile.pendingUpload;
        if (tile.texture == null || mask == null || mask.isEmpty() || tile.anaglyph != mc.gameSettings.anaglyph)
            return false;
        int left = PATCH_SIDE, top = PATCH_SIDE, right = 0, bottom = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1)) {
            int x = bit % PATCH_SIDE, y = bit / PATCH_SIDE;
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x + 1);
            bottom = Math.max(bottom, y + 1);
        }
        int width = (right - left) * TileImage.BLOCK, height = (bottom - top) * TileImage.BLOCK;
        if (width * height > Projection.TILE_PIXELS * Projection.TILE_PIXELS / 4) return false;
        int[] pixels = tile.pixels;
        DynamicTexture texture = tile.texture;
        int id = tile.id;
        long revision = tile.revision;
        boolean anaglyph = tile.anaglyph;
        long started = TerrainMetrics.ENABLED ? System.nanoTime() : 0;
        boolean ok = false;
        try {
            ok = HudTexturePatchUpload.upload(
                id,
                Projection.TILE_PIXELS,
                Projection.TILE_PIXELS,
                pixels,
                left * TileImage.BLOCK,
                top * TileImage.BLOCK,
                width,
                height,
                anaglyph,
                () -> current(mc) && mc.gameSettings.anaglyph == anaglyph
                    && tiles.get(key) == tile
                    && tile.texture == texture
                    && tile.pixels == pixels
                    && tile.pendingUpload == mask
                    && tile.revision == revision);
            if (ok) {
                tile.uploaded = pixels;
                tile.pendingUpload = new BitSet();
                textureGeneration++;
            }
        } catch (RuntimeException failure) {
            LOG.warn("Diorama HUD partial upload failed; reverting to full texture", failure);
        } finally {
            if (!ok && tile.texture == texture) {
                textureGeneration++;
                tile.release(state);
            }
            if (TerrainMetrics.ENABLED) {
                TerrainMetrics.record(TerrainMetrics.Phase.HUD_UPLOAD, System.nanoTime() - started);
                TerrainMetrics.add(
                    ok ? TerrainMetrics.Counter.HUD_UPLOAD_PUBLISHED : TerrainMetrics.Counter.HUD_UPLOAD_FAILED,
                    1);
            }
        }
        return true; // One attempted upload per frame. A tainted texture falls back next frame.
    }

    private void upload(Minecraft mc, TileKey key, CachedTile tile, ProbeGlState state) {
        int[] scratch = tile.pixels;
        boolean anaglyph = mc.gameSettings.anaglyph;
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
                () -> current(mc) && mc.gameSettings.anaglyph == anaglyph
                    && tiles.get(key) == tile
                    && tile.pixels == scratch
                    && tile.texture == old,
                texture -> {
                    tile.texture = texture;
                    tile.id = raw[0];
                    tile.uploaded = scratch;
                    tile.anaglyph = anaglyph;
                    tile.pendingUpload = new BitSet();
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

        private final BitSet dirty = new BitSet();
        private long revision;
        private int top;
        private boolean needsFull = true, anaglyph;
        private @Nullable BitSet pendingUpload;

        private void invalidate(BitSet patches) {
            if (patches.isEmpty()) return;
            dirty.or(patches);
            revision++;
        }

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
