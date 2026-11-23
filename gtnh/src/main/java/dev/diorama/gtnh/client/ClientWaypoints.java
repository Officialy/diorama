package dev.diorama.gtnh.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.WorldProviderSurface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

/** Client-world identity is independent of tile readiness and resource reloads. */
final class ClientWaypoints {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static @Nullable ClientWaypoints current;
    private final WorldClient world;
    private final @Nullable Path file;
    private long lock;
    private boolean unavailable;
    private LegacyWaypointBook book = new LegacyWaypointBook();

    private ClientWaypoints(Minecraft mc, WorldClient world) {
        this.world = world;
        Path save = ClientPreviewStorage.savePath(mc, world);
        file = save == null ? null : save.resolve("diorama-waypoints-v1-overworld.dat");
        if (file == null) {
            unavailable = mc.isIntegratedServerRunning();
            return;
        }
        try {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(save.resolve("session.lock")))) {
                lock = input.readLong();
                if (input.read() != -1) throw new IOException("Invalid save session lock");
            }
            book = LegacyWaypointBook.load(file, lock);
        } catch (IOException failure) {
            unavailable = true;
            LOG.warn("Could not open Diorama waypoints; existing data will be left untouched", failure);
        }
    }

    static boolean supported(@Nullable WorldClient world) {
        return world != null && world.provider != null
            && world.provider.getClass() == WorldProviderSurface.class
            && world.provider.dimensionId == 0
            && world.getHeight() == 256;
    }

    static void observe(Minecraft mc) {
        if (current != null && !current.valid(mc)) current = null;
        if (current == null && supported(mc.theWorld) && mc.thePlayer != null) {
            // Wait until the local server exposes a save identity rather than opening a RAM-only book for it.
            if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() == null) return;
            current = new ClientWaypoints(mc, mc.theWorld);
        }
    }

    static @Nullable ClientWaypoints active(Minecraft mc) {
        ClientWaypoints session = current;
        return session != null && session.valid(mc) ? session : null;
    }

    static List<Point> visibleBook(Minecraft mc) {
        ClientWaypoints session = active(mc);
        return session == null ? List.of() : session.points();
    }

    boolean valid(Minecraft mc) {
        return current == this && mc.theWorld == world && supported(world);
    }

    List<Point> points() {
        return book.points();
    }

    boolean writable() {
        return !unavailable;
    }

    boolean sessionOnly() {
        return file == null && !unavailable;
    }

    boolean change(Minecraft mc, Consumer<LegacyWaypointBook> edit) {
        if (!valid(mc) || unavailable) return false;
        LegacyWaypointBook updated = new LegacyWaypointBook();
        for (Point point : points()) updated.add(point);
        edit.accept(updated);
        if (file != null) {
            try {
                updated.save(file, lock);
            } catch (IOException failure) {
                LOG.warn("Could not save Diorama waypoints", failure);
                return false;
            }
        }
        book = updated;
        return true;
    }
}
