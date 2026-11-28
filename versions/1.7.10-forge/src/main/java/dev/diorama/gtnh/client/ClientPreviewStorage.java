package dev.diorama.gtnh.client;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.server.integrated.IntegratedServer;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.PreviewTileStore;
import dev.diorama.gtnh.capture.RemoteWorldIdentity;

/** Resolves an integrated save without opening its world data or keeping a server reference on the worker. */
final class ClientPreviewStorage {

    private static @Nullable Object remoteConnection;
    private static @Nullable String remoteEndpoint, remoteLabel;

    private ClientPreviewStorage() {}

    static @Nullable PreviewTileStore forWorld(Minecraft minecraft, WorldClient world) {
        ClientMapDimension dimension = ClientMapDimension.of(world);
        if (dimension == null) return null;
        Path save = savePath(minecraft, world);
        if (save == null) return null;
        try {
            return PreviewTileStore.forSave(save, dimension.suffix());
        } catch (IOException invalid) {
            return null;
        }
    }

    static @Nullable Path savePath(Minecraft minecraft, WorldClient world) {
        if (ClientMapDimension.of(world) == null || minecraft.theWorld != world) return null;
        if (minecraft.isIntegratedServerRunning()) return integratedSavePath(minecraft, world);
        ServerData server = minecraft.func_147104_D();
        if (server == null) return null;
        try {
            Object connection=minecraft.getNetHandler();
            if(connection==null)return null;
            if(connection!=remoteConnection||!server.serverIP.equals(remoteEndpoint)) {
                remoteConnection=connection;
                remoteEndpoint=server.serverIP;
                remoteLabel=null;
                String configured=System.getProperty("diorama.remoteServer");
                String label=System.getProperty("diorama.remoteWorld");
                remoteLabel=server.serverIP.equals(configured)&&label!=null?label:
                    RemoteWorldIdentity.profile(minecraft.mcDataDir.toPath(),server.serverIP);
            }
            return RemoteWorldIdentity.open(minecraft.mcDataDir.toPath(),server.serverIP,remoteEndpoint,remoteLabel);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    static void clearDisconnected(Minecraft mc) {
        if(mc.theWorld==null&&mc.getNetHandler()==null) {
            remoteConnection=null;
            remoteEndpoint=null;
            remoteLabel=null;
        }
    }

    static String remoteProfile(Minecraft mc,@Nullable String label,boolean change) {
        ServerData server=mc.func_147104_D();
        if(mc.theWorld==null||mc.isIntegratedServerRunning()||server==null)return "Remote server required";
        try {
            Path game=mc.mcDataDir.toPath();
            if(change) {
                RemoteWorldIdentity.setProfile(game,server.serverIP,label);
                String override=System.getProperty("diorama.remoteServer");
                if(server.serverIP.equals(override)&&System.getProperty("diorama.remoteWorld")!=null)
                    return "Profile saved; remove the remote JVM override before reconnecting";
                return label==null?"Remote map persistence disabled for next reconnect":
                    "Remote profile saved. Reconnect to activate; use a unique label for each proxy world.";
            }
            String stored=RemoteWorldIdentity.profile(game,server.serverIP);
            return new StringBuilder("Active label: ").append(remoteLabel==null?"session only":remoteLabel)
                .append("; saved for reconnect: ").append(stored==null?"session only":stored)
                .append("; /dioramaterrain world-label <label|off>").toString();
        } catch(IOException|RuntimeException failure) {
            return "Could not read/save remote profile; use a nonempty label of at most 128 characters";
        }
    }

    private static @Nullable Path integratedSavePath(Minecraft minecraft, WorldClient world) {
        if (minecraft.theWorld != world || !minecraft.isIntegratedServerRunning()) return null;
        IntegratedServer server = minecraft.getIntegratedServer();
        if (server == null) return null;
        String folder = server.getFolderName();
        if (folder == null || folder.isEmpty()) return null;
        try {
            Path name = Paths.get(folder);
            if (name.getRoot() != null || name.getNameCount() != 1
                || !name.toString()
                    .equals(folder)
                || folder.equals(".")
                || folder.equals("..")
                || folder.indexOf('/') >= 0
                || folder.indexOf('\\') >= 0) return null;
            // A deleted/recreated save does not inherit a cache from a global name-based directory.
            return minecraft.mcDataDir.toPath()
                .resolve("saves")
                .resolve(name);
        } catch (InvalidPathException invalid) {
            return null;
        }
    }
}
