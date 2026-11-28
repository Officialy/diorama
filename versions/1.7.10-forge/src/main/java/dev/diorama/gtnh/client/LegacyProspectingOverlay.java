package dev.diorama.gtnh.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Loader;
import dev.diorama.Projection;

/**
 * Optional read-only integration with VisualProspecting's client cache.
 * Does not inspect server caches, world blocks or send a prospecting request.
 * Instances belong to one open map screen and are discarded on world replacement.
 */
final class LegacyProspectingOverlay {
    private static final Logger LOG = LogManager.getLogger("Diorama");
    private static final String ROOT = "com.sinthoras.visualprospecting.database.";
    private static final int LIMIT = 8192;
    static final int REFERENCE_Y = 65;
    private List<Marker> markers = Collections.emptyList();
    private long nextRefresh;
    private int lastDimension = Integer.MIN_VALUE, lastMode;
    private boolean unavailable;

    static boolean installed() {
        return Loader.isModLoaded("visualprospecting");
    }

    void clear() {
        markers = Collections.emptyList();
        nextRefresh = 0;
    }

    String status() {
        return unavailable ? "VisualProspecting API unavailable" : "Discovered deposits (reference Y=65)";
    }

    List<Marker> visible(MapViewportController view, int dimension, int mode) {
        if (mode == 0 || !installed()) {
            clear();
            return markers;
        }
        long now = System.nanoTime();
        if (dimension != lastDimension || mode != lastMode || now - nextRefresh >= 0) {
            lastDimension = dimension;
            lastMode = mode;
            nextRefresh = now + 2_000_000_000L;
            try {
                markers = snapshot(dimension, mode);
                unavailable = false;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                markers = Collections.emptyList();
                if (!unavailable) LOG.warn("VisualProspecting client overlay unavailable", error);
                unavailable = true;
                nextRefresh = now + 10_000_000_000L;
            }
        }
        return markers;
    }

    private static List<Marker> snapshot(int dimension, int mode) throws ReflectiveOperationException {
        ClassLoader loader = LegacyProspectingOverlay.class.getClassLoader();
        Class<?> cacheType = Class.forName(ROOT + "ClientCache", false, loader);
        Object cache = cacheType.getField("instance").get(null);
        List<Marker> found = new ArrayList<>();
        if ((mode & 1) != 0) {
            Class<?> ore = Class.forName(ROOT + "OreVeinPosition", false, loader);
            Class<?> vein = Class.forName(ROOT + "veintypes.VeinType", false, loader);
            Field dim = ore.getField("dimensionId"), type = ore.getField("veinType");
            Object none = vein.getField("NO_VEIN").get(null);
            Method x = ore.getMethod("getBlockX"), z = ore.getMethod("getBlockZ");
            Method depleted = ore.getMethod("isDepleted"), name = vein.getMethod("getVeinName");
            Collection<?> records = (Collection<?>) cacheType.getMethod("getAllOreVeins").invoke(cache);
            for (Object record : records) {
                if (found.size() >= LIMIT) break;
                if (dim.getInt(record) != dimension) continue;
                Object kind = type.get(record);
                if (kind == null || kind == none) continue;
                String label = String.valueOf(name.invoke(kind));
                int px = ((Number)x.invoke(record)).intValue(), pz = ((Number)z.invoke(record)).intValue();
                if (valid(px,pz)) found.add(new Marker(px,pz,label,false,Boolean.TRUE.equals(depleted.invoke(record))));
            }
        }
        if ((mode & 2) != 0) {
            Class<?> fluid = Class.forName(ROOT + "UndergroundFluidPosition", false, loader);
            Field dim = fluid.getField("dimensionId"), type = fluid.getField("fluid");
            Method x = fluid.getMethod("getBlockX"), z = fluid.getMethod("getBlockZ");
            Method max = fluid.getMethod("getMaxProduction");
            Collection<?> records = (Collection<?>) cacheType.getMethod("getAllUndergroundFluids").invoke(cache);
            int fluids = 0;
            for (Object record : records) {
                if (fluids >= LIMIT) break;
                if (dim.getInt(record) != dimension) continue;
                Object kind = type.get(record);
                if (kind == null) continue;
                String label = String.valueOf(kind.getClass().getMethod("getName").invoke(kind));
                // VisualProspecting's fluid records refer to the corner of an 8x8 chunk area.
                int px = ((Number)x.invoke(record)).intValue() + 64, pz = ((Number)z.invoke(record)).intValue() + 64;
                if (valid(px,pz)) {
                    found.add(new Marker(px,pz,label,true,((Number)max.invoke(record)).intValue() <= 0));
                    fluids++;
                }
            }
        }
        return found;
    }

    private static boolean valid(int x,int z) {
        return x >= -29999984 && x < 29999984 && z >= -29999984 && z < 29999984;
    }

    static final class Marker {
        final int x,z;
        final String name;
        final boolean fluid,depleted;

        Marker(int x,int z,String name,boolean fluid,boolean depleted) {
            this.x=x; this.z=z; this.name=name; this.fluid=fluid; this.depleted=depleted;
        }

        double u() { return Projection.u(x+.5,z+.5); }
        double v() { return Projection.v(x+.5,REFERENCE_Y,z+.5); }
        int color() { return depleted ? 0xFF777777 : fluid ? 0xFF5CB5FF : 0xFFFFBA55; }
    }
}
