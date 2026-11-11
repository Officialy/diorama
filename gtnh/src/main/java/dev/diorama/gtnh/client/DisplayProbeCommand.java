package dev.diorama.gtnh.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/** Client-only development command, not a persistent product setting. */
public final class DisplayProbeCommand extends CommandBase {

    private final ClientProxy owner;

    public DisplayProbeCommand(ClientProxy owner) {
        this.owner = owner;
    }

    @Override
    public String getCommandName() {
        return "dioramaprobe";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dioramaprobe";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] arguments) {
        if (arguments.length != 0) {
            sender.addChatMessage(new ChatComponentText("Usage: /dioramaprobe"));
            return;
        }
        owner.requestDisplayProbe();
    }
}
