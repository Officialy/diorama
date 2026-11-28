package dev.diorama.gtnh.client;

import net.minecraft.client.Minecraft;
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
        return "/dioramaterrain [status|missing|import-waypoints]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) {
        if (arguments.length == 1 && ("status".equals(arguments[0]) || "missing".equals(arguments[0]))) {
            for (String line : ClientCaptureLifecycle.diagnostics("missing".equals(arguments[0]))) {
                sender.addChatMessage(new ChatComponentText(line));
            }
            return;
        }
        if (arguments.length == 1 && "import-waypoints".equals(arguments[0])) {
            Minecraft mc = Minecraft.getMinecraft();
            ClientWaypoints session = ClientWaypoints.active(mc);
            sender.addChatMessage(new ChatComponentText(session == null ? "Waypoint book unavailable"
                : session.importStaged(mc)));
            return;
        }
        if (arguments.length != 0) {
            sender.addChatMessage(new ChatComponentText("Usage: /dioramaterrain [status|missing|import-waypoints]"));
            return;
        }
        owner.requestTerrainPreview();
    }
}
