package dev.diorama.gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.diorama.gtnh.CommonProxy;

/** Diagnostic UI plus lifecycle identity observation; never installs a HUD or reads world blocks. */
public final class ClientProxy extends CommonProxy implements IResourceManagerReloadListener {

    private volatile int resourceGeneration;
    private WorldClient requestedWorld;
    private GuiChat terrainChat;
    private WorldClient terrainWorld;

    @Override
    public void init() {
        ClientCommandHandler.instance.registerCommand(new DisplayProbeCommand(this));
        ClientCommandHandler.instance.registerCommand(new TerrainPreviewCommand(this));
        MinecraftForge.EVENT_BUS.register(this);
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
            minecraft.displayGuiScreen(new TerrainPreviewScreen(minecraft.theWorld));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onGuiOpen(GuiOpenEvent event) {
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
            event.gui = new TerrainPreviewScreen(originatingWorld);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientCaptureLifecycle.observeWorld();
        if (requestedWorld == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onWorldUnload(WorldEvent.Unload event) {
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
