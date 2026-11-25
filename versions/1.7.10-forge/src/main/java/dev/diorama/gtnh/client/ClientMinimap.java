package dev.diorama.gtnh.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewOverview;
import dev.diorama.gtnh.capture.PreviewTileStore;
import dev.diorama.gtnh.capture.TerrainMetrics;
import dev.diorama.gtnh.capture.TerrainPreview;
import dev.diorama.gtnh.diagnostic.ProbePixels;

/** Shared bounded terrain session; fullscreen map and HUD consume the same resident raster and texture. */
public final class ClientMinimap {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final int CACHE_TILES = 128;
    private static final int MAP_TILES = 24;
    private static final int OVERVIEW_TILES = 16;
    private static final int PATCH_FALLBACK = 160;
    private static final int PATCH_SIDE = Projection.TILE_PIXELS / TileImage.BLOCK;
    private static final int CAPTURES_PER_TICK = 8;
    private static final long CAPTURE_BUDGET_NS = 3_000_000L;
    private static @Nullable ClientMinimap installed;

    private final Map<TileKey, CachedTile> tiles = new LinkedHashMap<>(CACHE_TILES + 1, 0.75f, true);
    private final Map<TileKey, CachedTile> overviews = new LinkedHashMap<>(OVERVIEW_TILES + 1, 0.75f, true);
    private final MinimapOptions options;
    private final SharedTileIo io = new SharedTileIo();
    private boolean persistent;
    private @Nullable WorldClient layerWorld;
    private boolean manualCave;
    private int manualCut = TileKey.SURFACE;
    private int layer = TileKey.SURFACE;
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
    private @Nullable WorldClient lastFrameWorld;
    private TerrainMetrics.@Nullable FrameView lastFrameView;
    private long lastFrameStart;
    private int retryTicks;

    ClientMinimap(Path optionsFile) {
        options = new MinimapOptions(optionsFile);
        try {
            options.load();
        } catch (IOException | IllegalArgumentException failure) {
            LOG.warn("Could not read Diorama minimap options; using defaults", failure);
        }
        installed = this;
    }

    static MinimapOptions options() {
        return java.util.Objects.requireNonNull(installed).options;
    }

    static boolean saveOptions() {
        try {
            options().save();
            return true;
        } catch (IOException failure) {
            LOG.warn("Could not save Diorama minimap options", failure);
            return false;
        }
    }

    static void toggle() {
        MinimapOptions settings = options();
        settings.visible = !settings.visible;
        saveOptions();
    }

    static int layer(Minecraft mc) {
        ClientMinimap hud = installed;
        return hud != null && hud.layerWorld == mc.theWorld ? hud.layer : TileKey.SURFACE;
    }

    static void toggleLayer(Minecraft mc) {
        ClientMinimap hud = installed;
        ClientMapDimension dimension = ClientMapDimension.of(mc.theWorld);
        if (hud != null && dimension != null && dimension.surfaceTerrain()) {
            hud.observeLayer(mc);
            hud.manualCave = !hud.manualCave;
            hud.manualCut = TileKey.SURFACE;
            hud.observeLayer(mc);
        }
    }

    static boolean lockedCaveHeight(Minecraft mc) {
        ClientMinimap hud = installed;
        return hud != null && hud.layerWorld == mc.theWorld && hud.manualCut != TileKey.SURFACE;
    }

    static void adjustCaveHeight(Minecraft mc, boolean up) {
        ClientMinimap hud = installed;
        if (hud == null || hud.layerWorld != mc.theWorld || hud.layer == TileKey.SURFACE) return;
        hud.manualCut = MinimapViewport.stepCaveLayer(hud.layer, up);
        hud.observeLayer(mc);
    }

    static void followCaveHeight(Minecraft mc) {
        ClientMinimap hud = installed;
        if (hud == null || hud.layerWorld != mc.theWorld) return;
        hud.manualCut = TileKey.SURFACE;
        hud.observeLayer(mc);
    }

    private void observeLayer(Minecraft mc) {
        if (layerWorld != mc.theWorld) {
            layerWorld = mc.theWorld;
            manualCave = false;
            manualCut = TileKey.SURFACE;
            layer = TileKey.SURFACE;
        }
        ClientMapDimension dimension = ClientMapDimension.of(mc.theWorld);
        EntityClientPlayerMP player = mc.thePlayer;
        int next = TileKey.SURFACE;
        if (player != null && dimension != null && (!dimension.surfaceTerrain() || manualCave)) {
            double feet = player.boundingBox.minY;
            if (manualCut != TileKey.SURFACE) next = manualCut;
            else if (feet >= 0 && feet < 256) next = MinimapViewport.caveLayer(MathHelper.floor_double(feet), layer);
        }
        if (next != layer) {
            layer = next;
            discardJob();
        }
    }

    static void frameStart(Minecraft mc) {
        ClientMinimap hud = installed;
        if (!TerrainMetrics.ENABLED || hud == null) return;
        WorldClient world = mc.theWorld;
        TerrainMetrics.FrameView view = null;
        if (world != null && mc.thePlayer != null && Display.isActive()) {
            if (mc.currentScreen instanceof SharedTerrainMapScreen map && map.belongsTo(world)) {
                view = TerrainMetrics.FrameView.MAP;
            } else if (mc.currentScreen == null && mc.inGameHasFocus
                && !mc.gameSettings.hideGUI
                && !mc.gameSettings.showDebugInfo) {
                    view = hud.options.visible ? TerrainMetrics.FrameView.HUD : TerrainMetrics.FrameView.HUD_HIDDEN;
                }
        }
        long now = view == null ? 0 : System.nanoTime();
        // The cadence is between comparable render starts, not the CPU cost of one GL call.
        if (view != null && view == hud.lastFrameView && world == hud.lastFrameWorld)
            TerrainMetrics.recordFrame(view, now - hud.lastFrameStart);
        hud.lastFrameWorld = view == null ? null : world;
        hud.lastFrameView = view;
        hud.lastFrameStart = now;
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

    public static void blockChanged(World world, int cx, int cz, int x, int y, int z) {
        TerrainWindow input = eventWindow(world);
        if (input == null || !input.contains(cx, cz)) return;
        input.blockChanged(cx, cz, x, z);
        for (var entry : installed.tiles.entrySet()) {
            entry.getValue()
                .invalidate(
                    DirtyTerrainTiles.block(entry.getKey(), cx * 16 + x, y, cz * 16 + z),
                    entry.getKey()
                        .isCave());
        }
    }

    public static void chunkFilled(World world, int cx, int cz, boolean full) {
        TerrainWindow input = eventWindow(world);
        if (input == null) return;
        input.chunkFilled(cx, cz, full);
        if (input.near(cx, cz)) installed.dirtyHalo(cx, cz);
    }

    public static void chunkUnloaded(World world, int cx, int cz) {
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
                        .range(entry.getKey(), cx * 16 - 16, 0, cz * 16 - 16, cx * 16 + 31, 255, cz * 16 + 31),
                    entry.getKey()
                        .isCave());
        }
    }

    private void dirtyChunk(int cx, int cz) {
        for (var entry : tiles.entrySet()) {
            if (!DirtyTerrainTiles.intersectsChunk(entry.getKey(), cx, cz)) continue;
            entry.getValue()
            .invalidate(
                DirtyTerrainTiles.chunk(entry.getKey(), cx, cz),
                entry.getKey()
                    .isCave());
        }
    }

    private boolean canShow(Minecraft mc) {
        return options.visible && !mc.gameSettings.hideGUI
            && !mc.gameSettings.showDebugInfo
            && (mc.currentScreen == null || mc.currentScreen instanceof GuiChat);
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

    private static int captureRadius(Minecraft mc) {
        int configured = Integer.getInteger("diorama.captureRadius", mc.gameSettings.renderDistanceChunks);
        return Math.max(1, Math.min(TerrainPreviewRefresh.MAX_RADIUS, configured));
    }

    private void update(Minecraft mc) {
        if (updating) return;
        updating = true;
        try {
            if (world != null && !current(mc)) reset();
            observeLayer(mc);
            if (retryTicks > 0) {
                retryTicks--;
                return;
            }
            SharedTerrainMapScreen map = mc.currentScreen instanceof SharedTerrainMapScreen screen
                && screen.belongsTo(mc.theWorld) ? screen : null;
            if (map == null && !canShow(mc)) {
                return; // Never schedule terrain under unrelated GUIs.
            }
            WorldClient active = mc.theWorld;
            EntityClientPlayerMP player = mc.thePlayer;
            ClientMapDimension dimension = ClientMapDimension.of(active);
            if (active == null || player == null
                || dimension == null
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
                PreviewTileStore store = dimension.surfaceTerrain() ? ClientPreviewStorage.forWorld(mc, active) : null;
                if (!current(mc)) return;
                io.reset(store);
                persistent = store != null;
            }
            if (!current(mc)) return;
            int cx = MathHelper.floor_double(player.posX) >> 4, cz = MathHelper.floor_double(player.posZ) >> 4;
            int radius = captureRadius(mc);
            if (window == null || cx != centerX || cz != centerZ || window.radius() != radius) {
                centerX = cx;
                centerZ = cz;
                window = new TerrainWindow(session, cx, cz, radius, () -> current(mc), window, this::dirtyChunk);
            }
            window.tick(CAPTURES_PER_TICK, CAPTURE_BUDGET_NS);
            if (!current(mc)) return;
            finish(mc);
            if (!current(mc)) return;
            io.drain(this::restore);
            flushWrites();
            List<TileKey> visible = map == null ? view(player.posX, player.boundingBox.minY, player.posZ, layer).tiles()
                .stream()
                .map(MinimapViewport.Tile::key)
                .collect(java.util.stream.Collectors.toList()) : mapWanted(map.viewport(), layer);
            List<TileKey> wanted = new ArrayList<>();
            for (TileKey key : visible) {
                if (key.lod() == 0) wanted.add(key);
                else for (int i = 0; i < 4; i++) wanted.add(PreviewOverview.childKey(key, i));
            }
            List<TileKey> live = new ArrayList<>();
            for (TileKey key : wanted) {
                boolean local = window.hasReady(key);
                if (local) live.add(key);
                boolean onDisk = persistent && !key.isCave();
                if (onDisk || local) {
                    CachedTile tile = detail(key);
                    if (onDisk && tile != null && !tile.readRequested) tile.readRequested = io.read(key);
                }
            }
            if (map != null && map.viewport()
                .preferredLod() == 1) buildOverview(visible);
            if (retryTicks > 0 || job != null || TerrainPreviewJob.isBusy() || window.ready() == 0)
                return;
            // Visible tiles first, then pre-bake the rest of the received render-distance footprint.
            List<TileKey> background = window.candidates(layer);
            double playerU = Projection.u(player.posX, player.posZ);
            double playerV = Projection.v(player.posX, player.boundingBox.minY, player.posZ);
            background.sort(Comparator.comparingDouble(key -> Math.hypot(
                key.minU() + key.units() * .5 - playerU, key.minV() + key.units() * .5 - playerV)));
            LinkedHashSet<TileKey> ordered = new LinkedHashSet<>(live);
            for (TileKey key : background) if (window.hasReady(key)) ordered.add(key);
            TileKey selected = choose(new ArrayList<>(ordered), window);
            if (selected == null) return;
            CachedTile entry = detail(selected);
            if (entry == null || !current(mc)) return;
            long revision = entry.revision;
            TerrainPreview.Work work = window.prepare(selected);
            if (!current(mc) || entry.revision != revision) return;
            BitSet patches = !selected.isCave() && entry.pixels != null
                && !entry.needsFull
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

    private @Nullable TileKey choose(List<TileKey> wanted, TerrainWindow input) {
        TileKey stale = null;
        for (TileKey key : wanted) {
            CachedTile entry = tiles.get(key);
            if (entry == null || entry.pixels == null) return key;
            if (stale == null && (entry.needsFull || !entry.dirty.isEmpty() || entry.top != input.topY(key)))
                stale = key;
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
        if (entry == null || jobRevision != entry.revision || key.layer() != layer || window.topY(key) != jobTop)
            return;
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
            entry.savePending = persistent && !key.isCave();
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

    private @Nullable CachedTile detail(TileKey key) {
        CachedTile tile = tiles.get(key);
        if (tile != null) return tile;
        if (tiles.size() >= CACHE_TILES && !evict(tiles, true)) return null;
        tile = new CachedTile();
        tiles.put(key, tile);
        return tile;
    }

    private boolean evict(Map<TileKey, CachedTile> cache, boolean detail) {
        var entries = cache.entrySet()
            .iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            CachedTile oldest = entry.getValue();
            if (detail && (entry.getKey()
                .equals(jobKey) || oldest.savePending)) continue;
            entries.remove();
            textureGeneration++;
            if (oldest.texture != null) try (ProbeGlState state = new ProbeGlState()) {
                oldest.release(state);
            }
            return true;
        }
        return false;
    }

    private void restore(SharedTileIo.Read result) {
        if (result.key()
            .isCave()) return;
        CachedTile tile = tiles.get(result.key());
        if (tile == null) return;
        if (result.failure() != null) {
            LOG.warn("Diorama tile cache read failed: {}", result.key(), result.failure());
            return;
        }
        if (result.pixels() == null) return;
        int[] merged = PreviewTileStore.retain(result.pixels(), tile.pixels);
        if (merged != tile.pixels) {
            // A patch worker may hold the earlier history raster. Reject that job rather than
            // letting it overwrite pixels restored after its submission.
            tile.revision++;
            tile.pixels = merged;
            tile.pendingUpload = null;
        }
    }

    private void flushWrites() {
        if (!persistent) return;
        for (var entry : tiles.entrySet()) {
            CachedTile tile = entry.getValue();
            if (tile.savePending && io.write(entry.getKey(), tile.pixels)) tile.savePending = false;
        }
    }

    private void buildOverview(List<TileKey> visible) {
        int edge = Projection.TILE_PIXELS, half = edge / 2;
        for (TileKey key : visible) {
            CachedTile parent = overviews.get(key);
            int[][] children = new int[4][];
            boolean changed = false;
            for (int i = 0; i < 4; i++) {
                CachedTile child = tiles.get(PreviewOverview.childKey(key, i));
                children[i] = child == null ? null : child.pixels;
                if (children[i] != null && (parent == null || parent.children[i] != children[i])) changed = true;
            }
            if (!changed) continue;
            int[] pixels = PreviewOverview.build(key, children);
            if (parent == null) {
                if (overviews.size() >= OVERVIEW_TILES) evict(overviews, false);
                parent = new CachedTile();
                overviews.put(key, parent);
            } else for (int i = 0; i < 4; i++) if (children[i] == null && parent.children[i] != null) {
                // A child evicted from the detail cache leaves its already known quadrant intact.
                int start = (i / 2) * half * edge + (i % 2) * half;
                for (int y = 0; y < half; y++)
                    System.arraycopy(parent.pixels, start + y * edge, pixels, start + y * edge, half);
                children[i] = parent.children[i];
            }
            parent.children = children;
            parent.pixels = pixels;
            return; // Bound overview composition to one tile per tick, only while zoomed out.
        }
    }

    private void reset() {
        flushWrites();
        io.reset(null);
        persistent = false;
        discardJob();
        window = null;
        session = null;
        world = null;
        List<CachedTile> released = new ArrayList<>(tiles.values());
        released.addAll(overviews.values());
        tiles.clear();
        overviews.clear();
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

    private static MinimapViewport view(double x, double y, double z, int layer) {
        return new MinimapViewport(Projection.u(x, z), Projection.v(x, y, z), layer);
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
        int drawnLayer = layer;
        MinimapViewport view = view(x, y, z, drawnLayer);
        ClientCaptureLifecycle.Session shown = session;
        try (ProbeGlState state = new ProbeGlState()) {
            drawing = state;
            for (MinimapViewport.Tile part : view.tiles()) {
                CachedTile tile = tiles.get(part.key());
                if (tile != null && tile.pixels != null
                    && (tile.pixels != tile.uploaded || tile.anaglyph != mc.gameSettings.anaglyph)) {
                    if (!uploadPatch(mc, part.key(), tile, state, false)) upload(mc, part.key(), tile, state, false);
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
                    ClientWaypoints.visibleBook(mc),
                    options.size(),
                    yaw,
                    options.coordinates
                        ? MathHelper.floor_double(x) + ", "
                            + MathHelper.floor_double(y)
                            + ", "
                            + MathHelper.floor_double(z)
                        : "",
                    () -> current(mc) && canShow(mc)
                        && layer == drawnLayer
                        && session == shown
                        && textureGeneration == shownTextures);
        } catch (RuntimeException failure) {
            drawing = null; // The try-with-resources state has already been restored.
            backoff(failure);
        } finally {
            drawing = null;
        }
    }

    private static List<TileKey> mapWanted(MapViewportController view, int layer) {
        int lod = view.preferredLod();
        return view.tiles(layer, lod)
            .stream()
            .map(MapViewportController.Tile::key)
            .sorted(
                Comparator.comparingDouble(
                    key -> Math.hypot(
                        key.minU() + key.units() * .5 - view.centerU(),
                        key.minV() + key.units() * .5 - view.centerV())))
            .limit(lod == 0 ? MAP_TILES : OVERVIEW_TILES)
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
        int drawnLayer = layer;
        try (ProbeGlState state = new ProbeGlState()) {
            drawing = state;
            Map<TileKey, CachedTile> cache = view.preferredLod() == 0 ? tiles : overviews;
            for (TileKey key : mapWanted(view, drawnLayer)) {
                CachedTile tile = cache.get(key);
                if (tile != null && tile.pixels != null
                    && (tile.pixels != tile.uploaded || tile.anaglyph != mc.gameSettings.anaglyph)) {
                    if (!uploadPatch(mc, key, tile, state, true)) upload(mc, key, tile, state, true);
                    break;
                }
            }
            var keys = List.copyOf(cache.keySet());
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
                if (key.layer() != drawnLayer) continue;
                MapViewportController.Tile part = view.clip(key);
                if (part == null) continue;
                CachedTile tile = cache.get(key);
                if (tile == null || tile.id <= 0) continue;
                if (!mapCurrent(mc, screen, shown, shownTextures, drawnLayer)) return;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tile.id);
                if (!mapCurrent(mc, screen, shown, shownTextures, drawnLayer)) return;
                if (mesh == null) mesh = new Tessellator();
                mesh.startDrawingQuads();
                mesh.addVertexWithUV(part.x0(), part.y1(), 0, part.u0(), part.v1());
                mesh.addVertexWithUV(part.x1(), part.y1(), 0, part.u1(), part.v1());
                mesh.addVertexWithUV(part.x1(), part.y0(), 0, part.u1(), part.v0());
                mesh.addVertexWithUV(part.x0(), part.y0(), 0, part.u0(), part.v0());
                if (!mapCurrent(mc, screen, shown, shownTextures, drawnLayer)) return;
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
        long shownTextures, int drawnLayer) {
        return layer == drawnLayer && current(mc)
            && mc.currentScreen == screen
            && screen.belongsTo(mc.theWorld)
            && session == shown
            && textureGeneration == shownTextures;
    }

    private @Nullable CachedTile tileFor(TileKey key) {
        return (key.lod() == 0 ? tiles : overviews).get(key);
    }

    private boolean uploadPatch(Minecraft mc, TileKey key, CachedTile tile, ProbeGlState state, boolean map) {
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
                    && tileFor(key) == tile
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
            recordUpload(map, started, ok, !ok);
        }
        return true; // One attempted upload per frame. A tainted texture falls back next frame.
    }

    private void upload(Minecraft mc, TileKey key, CachedTile tile, ProbeGlState state, boolean map) {
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
                if (current(mc) && tileFor(key) == tile && tile.pixels == scratch) texture.updateDynamicTexture();
            },
                () -> current(mc) && mc.gameSettings.anaglyph == anaglyph
                    && tileFor(key) == tile
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
            recordUpload(map, started, published, failed);
        }
        if (published && old != null) {
            state.forgetDeletedTexture(oldId);
            old.deleteGlTexture();
        }
    }

    private static void recordUpload(boolean map, long started, boolean published, boolean failed) {
        if (!TerrainMetrics.ENABLED) return;
        TerrainMetrics.record(
            map ? TerrainMetrics.Phase.MAP_UPLOAD : TerrainMetrics.Phase.HUD_UPLOAD,
            System.nanoTime() - started);
        TerrainMetrics.Counter outcome = failed
            ? (map ? TerrainMetrics.Counter.MAP_UPLOAD_FAILED : TerrainMetrics.Counter.HUD_UPLOAD_FAILED)
            : published
                ? (map ? TerrainMetrics.Counter.MAP_UPLOAD_PUBLISHED : TerrainMetrics.Counter.HUD_UPLOAD_PUBLISHED)
                : (map ? TerrainMetrics.Counter.MAP_UPLOAD_REJECTED : TerrainMetrics.Counter.HUD_UPLOAD_REJECTED);
        TerrainMetrics.add(outcome, 1);
    }

    private static final class CachedTile {

        private final BitSet dirty = new BitSet();
        private long revision;
        private boolean readRequested, savePending;
        private int[][] children;
        private int top;
        private boolean needsFull = true, anaglyph;
        private @Nullable BitSet pendingUpload;

        private void invalidate(BitSet patches, boolean full) {
            if (full) {
                needsFull = true;
                revision++;
                return;
            }
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
