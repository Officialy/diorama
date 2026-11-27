package dev.diorama.gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.diorama.gtnh.CommonProxy;
import dev.diorama.gtnh.capture.TerrainMetrics;

/** Client bindings, UI and lifecycle event wiring. */
public final class ClientProxy extends CommonProxy implements IResourceManagerReloadListener {

    private static final String KEY_CATEGORY = "key.category.diorama.map";
    private static final KeyBinding OPEN_MAP = new KeyBinding("key.diorama.open_map", Keyboard.KEY_M, KEY_CATEGORY);
    private static final KeyBinding TOGGLE_MINIMAP = new KeyBinding(
        "key.diorama.toggle_minimap",
        Keyboard.KEY_NONE,
        KEY_CATEGORY);

    private static final KeyBinding NEW_WAYPOINT = new KeyBinding(
        "key.diorama.new_waypoint",
        Keyboard.KEY_B,
        KEY_CATEGORY);
    private static final KeyBinding WAYPOINT_LIST = new KeyBinding(
        "key.diorama.waypoint_list",
        Keyboard.KEY_U,
        KEY_CATEGORY);

    private volatile int resourceGeneration;
    private WorldClient inputWorld;
    private boolean openMap, toggleMinimap, newWaypoint, waypointList;
    private WorldClient requestedWorld;
    private GuiChat terrainChat;
    private WorldClient terrainWorld;

    @Override
    public void init() {
        ClientCommandHandler.instance.registerCommand(new DisplayProbeCommand(this));
        ClientCommandHandler.instance.registerCommand(new TerrainPreviewCommand(this));
        ClientRegistry.registerKeyBinding(OPEN_MAP);
        ClientRegistry.registerKeyBinding(TOGGLE_MINIMAP);
        ClientRegistry.registerKeyBinding(NEW_WAYPOINT);
        ClientRegistry.registerKeyBinding(WAYPOINT_LIST);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(
            new ClientMinimap(
                Minecraft.getMinecraft().mcDataDir.toPath()
                    .resolve("config")
                    .resolve("diorama-minimap.properties")));
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        ((IReloadableResourceManager) Minecraft.getMinecraft()
            .getResourceManager()).registerReloadListener(this);
    }

    void requestDisplayProbe() {
        requestedWorld = Minecraft.getMinecraft().theWorld;
    }

    void requestTerrainPreview() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!minecraft.func_152345_ab()) throw new IllegalStateException("Terrain command requires client thread");
        terrainChat = null;
        terrainWorld = null;
        if (minecraft.theWorld == null) return;
        if (minecraft.currentScreen instanceof GuiChat chat) {
            terrainChat = chat;
            terrainWorld = minecraft.theWorld;
        } else {
            minecraft.displayGuiScreen(new SharedTerrainMapScreen(minecraft.theWorld));
        }
    }

    static boolean isMapKey(int eventCode) {
        return matches(OPEN_MAP, eventCode);
    }

    static boolean isMapMouseButton(int button) {
        return button >= 0 && isMapKey(mouseKeyCode(button));
    }

    private static int mouseKeyCode(int button) {
        return button - 100; // The encoding used by 1.7.10 Controls.
    }

    private static boolean matches(KeyBinding binding, int eventCode) {
        return eventCode != Keyboard.KEY_NONE && eventCode == binding.getKeyCode();
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) recordPress(Keyboard.getEventKey());
    }

    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
        int button = Mouse.getEventButton();
        if (button >= 0 && Mouse.getEventButtonState()) recordPress(mouseKeyCode(button));
    }

    private void recordPress(int eventCode) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!acceptsInput(minecraft)) return;
        if (isMapKey(eventCode)) openMap = true;
        else if (matches(TOGGLE_MINIMAP, eventCode)) toggleMinimap = !toggleMinimap;
        else if (matches(NEW_WAYPOINT, eventCode)) newWaypoint = true;
        else if (matches(WAYPOINT_LIST, eventCode)) waypointList = true;
    }

    private static boolean inGameplay(Minecraft minecraft) {
        return minecraft.currentScreen == null && minecraft.inGameHasFocus && minecraft.thePlayer != null;
    }

    private boolean acceptsInput(Minecraft minecraft) {
        return inputWorld != null && minecraft.theWorld == inputWorld && inGameplay(minecraft);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onGuiOpen(GuiOpenEvent event) {
        if (!event.isCanceled() && event.gui != null) inputWorld = null;
        if (terrainChat == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        GuiChat originating = terrainChat;
        WorldClient originatingWorld = terrainWorld;
        // Consume precisely one transition, including cancellation or another mod replacing null.
        terrainChat = null;
        terrainWorld = null;
        if (!event.isCanceled() && event.gui == null
            && minecraft.currentScreen == originating
            && minecraft.theWorld == originatingWorld) {
            event.gui = new SharedTerrainMapScreen(originatingWorld);
        }
    }

    /** Health packet observation complements the tick fallback; never mutate the book off-thread. */
    public static void healthPacketApplied() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.func_152345_ab() && mc.theWorld != null && mc.thePlayer != null) ClientWaypoints.observe(mc);
    }

    /** Camera-facing markers rendered in the existing world pass; never query/load terrain. */
    @SubscribeEvent
    public void onWorldWaypoints(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc.renderViewEntity;
        if (viewer == null || mc.theWorld == null || mc.gameSettings.hideGUI || mc.currentScreen != null
            || !ClientMinimap.options().worldWaypoints) return;
        var points = ClientWaypoints.visibleBook(mc);
        if (points.isEmpty()) return;
        double cameraX = RenderManager.renderPosX, cameraY = RenderManager.renderPosY, cameraZ = RenderManager.renderPosZ;
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try (ProbeGlState state = new ProbeGlState()) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            for (var point : points) {
                if (!point.enabled()) continue;
                double dx = point.x() + .5 - cameraX, dy = point.y() + 1.5 - cameraY, dz = point.z() + .5 - cameraZ;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance < 0.25 || !Double.isFinite(distance)) continue;
                double reach = Math.min(1, 64 / distance);
                double scale = Math.max(.025, Math.min(distance, 64) * .0025);
                String label = point.name() + " (" + Math.round(distance) + "m)";
                GL11.glPushMatrix();
                try {
                    GL11.glTranslated(dx * reach, dy * reach, dz * reach);
                    GL11.glRotatef(-RenderManager.instance.playerViewY, 0, 1, 0);
                    GL11.glRotatef(RenderManager.instance.playerViewX, 1, 0, 0);
                    GL11.glScaled(-scale, -scale, scale);
                    int half = mc.fontRenderer.getStringWidth(label) / 2;
                    Gui.drawRect(-half - 3, -12, half + 3, 1, 0xB0101418);
                    Gui.drawRect(-3, 2, 3, 8, 0xFF000000 | point.markerColor());
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    mc.fontRenderer.drawStringWithShadow(label, -half, -10, 0xFF000000 | point.markerColor());
                } finally {
                    GL11.glPopMatrix();
                }
            }
        } finally {
            GL11.glDepthMask(depthWrite);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START && TerrainMetrics.ENABLED)
            ClientMinimap.frameStart(Minecraft.getMinecraft());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (event.phase == TickEvent.Phase.START) {
            // A GUI or the first focus click must not fall through into a gameplay hotkey.
            inputWorld = inGameplay(minecraft) ? minecraft.theWorld : null;
            openMap = toggleMinimap = newWaypoint = waypointList = false;
            return;
        }
        ClientCaptureLifecycle.observeWorld();
        ClientWaypoints.observe(minecraft);
        finishBindingInput(minecraft);
        ClientMinimap.tick(minecraft);
        if (requestedWorld == null) return;
        if (minecraft.theWorld != requestedWorld) {
            requestedWorld = null;
        } else if (minecraft.currentScreen == null) {
            // GuiChat closes itself after executing a command; open on the next client tick.
            requestedWorld = null;
            minecraft.displayGuiScreen(new DisplayProbeScreen(this));
        } else if (!(minecraft.currentScreen instanceof GuiChat)) {
            // Do not surprise the user by opening later after an unrelated GUI closes.
            requestedWorld = null;
        }
    }

    private void finishBindingInput(Minecraft minecraft) {
        // Vanilla still queues these registrations; dispatch only from the actual input events.
        while (OPEN_MAP.isPressed()) {}
        while (TOGGLE_MINIMAP.isPressed()) {}
        while (NEW_WAYPOINT.isPressed()) {}
        while (WAYPOINT_LIST.isPressed()) {}
        boolean accepted = acceptsInput(minecraft);
        inputWorld = null;
        if (!accepted) return;
        if (toggleMinimap) ClientMinimap.toggle();
        if (requestedWorld != null) return;
        if (openMap) minecraft.displayGuiScreen(new SharedTerrainMapScreen(minecraft.theWorld));
        else if (newWaypoint || waypointList) {
            ClientWaypoints session = ClientWaypoints.active(minecraft);
            if (session == null) return;
            if (newWaypoint && session.writable()
                && session.points()
                    .size() < LegacyWaypointBook.MAX_POINTS)
                WaypointEditScreen.openNew(minecraft, session, null);
            else minecraft.displayGuiScreen(new WaypointListScreen(session, null));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == inputWorld) inputWorld = null;
        if (event.world == terrainWorld) {
            terrainChat = null;
            terrainWorld = null;
        }
        if (event.world instanceof WorldClient old) ClientCaptureLifecycle.unloaded(old);
    }

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        // No GL here. A visible diagnostic screen rebuilds in its next draw call.
        resourceGeneration++;
    }

    int resourceGeneration() {
        return resourceGeneration;
    }
}
