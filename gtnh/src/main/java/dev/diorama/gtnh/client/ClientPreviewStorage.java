package dev.diorama.gtnh.client;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.server.integrated.IntegratedServer;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.PreviewTileStore;

/** Resolves an integrated save without opening its world data or keeping a server reference on the worker. */
final class ClientPreviewStorage {

    private ClientPreviewStorage() {}

    static @Nullable PreviewTileStore forWorld(Minecraft minecraft, WorldClient world) {
        Path save = savePath(minecraft, world);
        if (save == null) return null;
        try {
            return PreviewTileStore.forSave(save);
        } catch (IOException invalid) {
            return null;
        }
    }

    static @Nullable Path savePath(Minecraft minecraft, WorldClient world) {
        if (minecraft.theWorld != world || world.provider == null
            || world.provider.dimensionId != 0
            || !minecraft.isIntegratedServerRunning()) return null;
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
