package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.LegacyGeometryMath;

/**
 * Opt-in passive capture of legacy world-renderer vertices. Never invokes a renderer, mutates the
 * tessellator or retains live world objects. A sample represents one state, not every neighbour or
 * tile-entity configuration. Only the appearance owner's thread may collect or consume samples.
 */
public final class LegacyGeometryCapture {

    private static final boolean ENABLED = Boolean.getBoolean("diorama.experimentalGeometry");
    private static final int MAX_VERTICES = 256;
    private static final int MAX_STATES = 512;
    private static final float[] VERTICES = new float[MAX_VERTICES * 5];
    private static final Map<Long, float[]> SAMPLES = new HashMap<>();
    private static volatile @Nullable Thread owner;
    private static boolean collecting, changed;
    private static long key;
    private static int originX, originY, originZ, count;

    private LegacyGeometryCapture() {}

    static void reset() {
        if (!ENABLED) return;
        clear();
        owner = Thread.currentThread();
    }

    static void clear() {
        collecting = false;
        changed = false;
        SAMPLES.clear();
        owner = null;
    }

    private static long key(int rawId, int metadata) {
        return ((long) rawId << 16) | (metadata & 65535L);
    }

    static float @Nullable [] sample(int rawId, int metadata) {
        if (!ENABLED || owner != Thread.currentThread()) return null;
        return SAMPLES.get(key(rawId, metadata));
    }

    /** Called only by the existing tick/capture pipeline, never from an injected renderer hook. */
    static boolean consumeChanged() {
        if (!ENABLED || owner != Thread.currentThread() || !changed) return false;
        changed = false;
        return true;
    }

    public static void begin(Block block, @Nullable IBlockAccess access, int x, int y, int z) {
        if (!ENABLED || owner != Thread.currentThread()) return;
        collecting = false;
        if (access == null || SAMPLES.size() >= MAX_STATES) return;
        try {
            int renderType = block.getRenderType();
            if (renderType < 42 && renderType != 11 && renderType != 18 && renderType != 32) return;
            int rawId = Block.getIdFromBlock(block), metadata = access.getBlockMetadata(x, y, z);
            if (rawId < 0 || metadata < 0 || metadata > 65535) return;
            long state = key(rawId, metadata);
            if (SAMPLES.containsKey(state)) return;
            key = state;
            originX = x;
            originY = y;
            originZ = z;
            count = 0;
            collecting = true;
        } catch (RuntimeException | LinkageError unsupported) {
            collecting = false;
        }
    }

    public static void vertex(double x, double y, double z, double u, double v) {
        if (!ENABLED || owner != Thread.currentThread() || !collecting) return;
        x -= originX;
        y -= originY;
        z -= originZ;
        if (count == MAX_VERTICES || !local(x) || !local(y) || !local(z) || !local(u) || !local(v)) {
            collecting = false;
            return;
        }
        int i = count++ * 5;
        VERTICES[i] = quantize(x);
        VERTICES[i + 1] = quantize(y);
        VERTICES[i + 2] = quantize(z);
        VERTICES[i + 3] = (float) Math.max(0, Math.min(1, u));
        VERTICES[i + 4] = (float) Math.max(0, Math.min(1, v));
    }

    private static boolean local(double coordinate) {
        return Double.isFinite(coordinate) && coordinate >= -0.001 && coordinate <= 1.001;
    }

    private static float quantize(double coordinate) {
        return Math.max(0, Math.min(32, Math.round(coordinate * 32))) / 32F;
    }

    public static void end(Block block, int x, int y, int z, boolean rendered) {
        if (!ENABLED || owner != Thread.currentThread() || !collecting) return;
        collecting = false;
        if (!rendered || x != originX
            || y != originY
            || z != originZ
            || count < 8
            || count % 4 != 0
            || Block.getIdFromBlock(block) != (int) (key >>> 16)) return;
        float[] sample = Arrays.copyOf(VERTICES, count * 5);
        if (LegacyGeometryMath.bounds(sample) != null && SAMPLES.size() < MAX_STATES) {
            SAMPLES.put(key, sample);
            changed = true;
        }
    }

}
