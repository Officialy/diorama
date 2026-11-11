package dev.diorama.gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraftforge.client.ClientCommandHandler;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.diorama.gtnh.CommonProxy;

/** Diagnostic registration only; never installs a HUD or reads world blocks. */
public final class ClientProxy extends CommonProxy implements IResourceManagerReloadListener {

    private volatile int resourceGeneration;
    private WorldClient requestedWorld;

    @Override
    public void init() {
        ClientCommandHandler.instance.registerCommand(new DisplayProbeCommand(this));
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        ((IReloadableResourceManager) Minecraft.getMinecraft()
            .getResourceManager()).registerReloadListener(this);
    }

    void requestDisplayProbe() {
        requestedWorld = Minecraft.getMinecraft().theWorld;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || requestedWorld == null) return;
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

    @Override
    public void onResourceManagerReload(IResourceManager manager) {
        // No GL here. A visible diagnostic screen rebuilds in its next draw call.
        resourceGeneration++;
    }

    int resourceGeneration() {
        return resourceGeneration;
    }
}
