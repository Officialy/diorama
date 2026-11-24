package dev.diorama.gtnh.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/** Independent bounded terrain preview, not a map/HUD toggle. */
public final class TerrainPreviewCommand extends CommandBase {

    private final ClientProxy owner;

    public TerrainPreviewCommand(ClientProxy owner) {
        this.owner = owner;
    }

    @Override
    public String getCommandName() {
        return "dioramaterrain";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dioramaterrain";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) {
        if (arguments.length != 0) {
            sender.addChatMessage(new ChatComponentText("Usage: /dioramaterrain (development diagnostic)"));
            return;
        }
        owner.requestTerrainPreview();
    }
}
