package dev.diorama.gtnh.client;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.MathHelper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

/** Client-world identity is independent of tile readiness and resource reloads. */
final class ClientWaypoints {

    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static @Nullable ClientWaypoints current;
    private final WorldClient world;
    private final int dimensionId;
    private final @Nullable Path file;
    private long lock;
    private boolean unavailable;
    private boolean deathRecorded;
    private LegacyWaypointBook book = new LegacyWaypointBook();

    private ClientWaypoints(Minecraft mc, WorldClient world, ClientMapDimension dimension) {
        this.world = world;
        this.dimensionId = dimension.id();
        Path save = ClientPreviewStorage.savePath(mc, world);
        file = save == null ? null : save.resolve("diorama-waypoints-v1-" + dimension.suffix() + ".dat");
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
        return ClientMapDimension.of(world) != null;
    }

    static void observe(Minecraft mc) {
        // Remote persistence requires a deliberate endpoint/world label; never reuse a live RAM book
        // across a replacement WorldClient, even across a dimension change.
        if (current != null && !current.valid(mc)) current = null;
        ClientMapDimension dimension = ClientMapDimension.of(mc.theWorld);
        if (current == null && dimension != null && mc.thePlayer != null) {
            // Wait until the local server exposes a save identity rather than opening a RAM-only book for it.
            if (mc.isIntegratedServerRunning() && mc.getIntegratedServer() == null) return;
            current = new ClientWaypoints(mc, mc.theWorld, dimension);
        }
        if (current != null) current.observeDeath(mc);
    }

    private void observeDeath(Minecraft mc) {
        var player = mc.thePlayer;
        if (player == null || !valid(mc)) return;
        if (player.getHealth() > 0 && !player.isDead) {
            deathRecorded = false;
            return;
        }
        if (deathRecorded) return;
        deathRecorded = true;
        if (!ClientMinimap.options().deathWaypoints || !writable()
            || points().size() >= LegacyWaypointBook.MAX_POINTS) return;
        try {
            int x = MathHelper.floor_double(player.posX);
            int y = Math.max(0, Math.min(255, MathHelper.floor_double(player.boundingBox.minY)));
            int z = MathHelper.floor_double(player.posZ);
            Point death = new Point("Death @ " + x + "," + y + "," + z, x, y, z, true);
            change(mc, updated -> updated.add(death));
        } catch (IllegalArgumentException invalid) {
            LOG.warn("Diorama skipped invalid death waypoint", invalid);
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
        if (current != this || mc.theWorld != world) return false;
        ClientMapDimension dimension = ClientMapDimension.of(world);
        return dimension != null && dimension.id() == dimensionId;
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

    /** Explicit, dimension-scoped import; the source files are never modified. */
    String importStaged(Minecraft mc) {
        if (!valid(mc) || !writable()) return "Waypoint book unavailable";
        Path folder = mc.mcDataDir.toPath().resolve("diorama-waypoint-import");
        try {
            List<Point> imported = LegacyWaypointBook.readImports(folder, dimensionId);
            if (imported.isEmpty()) return "No valid points for this dimension. Copy JourneyMap 5 JSON or Xaero TXT here: " + folder;
            int before = points().size();
            if (!change(mc, updated -> updated.merge(imported))) return "Waypoint import could not be saved";
            return "Imported " + (points().size() - before) + " of " + imported.size() + " matching points (duplicates/capacity skipped)";
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Waypoint import failed", failure);
            return "Waypoint import failed; check the import folder and log";
        }
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
