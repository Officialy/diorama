package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

import org.jspecify.annotations.Nullable;

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
    private static final Map<Long, float[]> BOXES = new HashMap<>();
    private static volatile @Nullable Thread owner;
    private static boolean collecting;
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
        BOXES.clear();
        owner = null;
    }

    private static long key(int rawId, int metadata) {
        return ((long) rawId << 16) | (metadata & 65535L);
    }

    static float @Nullable [] boxes(int rawId, int metadata) {
        if (!ENABLED || owner != Thread.currentThread()) return null;
        return BOXES.get(key(rawId, metadata));
    }

    public static void begin(Block block, @Nullable IBlockAccess access, int x, int y, int z) {
        if (!ENABLED || owner != Thread.currentThread()) return;
        collecting = false;
        if (access == null || BOXES.size() >= MAX_STATES) return;
        try {
            int renderType = block.getRenderType();
            if (renderType < 42 && renderType != 11 && renderType != 18 && renderType != 32) return;
            int rawId = Block.getIdFromBlock(block), metadata = access.getBlockMetadata(x, y, z);
            if (rawId < 0 || metadata < 0 || metadata > 65535) return;
            long state = key(rawId, metadata);
            if (BOXES.containsKey(state)) return;
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
        if (count == MAX_VERTICES || !local(x)
            || !local(y)
            || !local(z)
            || !Double.isFinite(u)
            || !Double.isFinite(v)) {
            collecting = false;
            return;
        }
        int i = count++ * 5;
        VERTICES[i] = quantize(x);
        VERTICES[i + 1] = quantize(y);
        VERTICES[i + 2] = quantize(z);
        VERTICES[i + 3] = (float) u;
        VERTICES[i + 4] = (float) v;
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
        float[] result = bounds();
        if (result != null && BOXES.size() < MAX_STATES) BOXES.put(key, result);
    }

    /** Match horizontal quad footprints into cuboids; otherwise retain a coarse enclosing box. */
    private static float @Nullable [] bounds() {
        float[] all = { 1, 1, 1, 0, 0, 0 };
        float[] faces = new float[MAX_VERTICES / 4 * 6];
        int faceCount = 0;
        for (int q = 0; q < count; q += 4) {
            float[] face = { 1, 1, 1, 0, 0, 0 };
            for (int v = q; v < q + 4; v++) {
                for (int axis = 0; axis < 3; axis++) {
                    float value = VERTICES[v * 5 + axis];
                    face[axis] = Math.min(face[axis], value);
                    face[axis + 3] = Math.max(face[axis + 3], value);
                    all[axis] = Math.min(all[axis], value);
                    all[axis + 3] = Math.max(all[axis + 3], value);
                }
            }
            if (face[1] == face[4] && face[0] < face[3] && face[2] < face[5]) {
                System.arraycopy(face, 0, faces, faceCount++ * 6, 6);
            }
        }
        if (!volume(all)) return null;
        float[] result = new float[96];
        float[] covered = { 1, 1, 1, 0, 0, 0 };
        int used = 0;
        for (int a = 0; a < faceCount; a++) {
            int i = a * 6;
            int partner = -1;
            float distance = Float.POSITIVE_INFINITY;
            for (int b = 0; b < faceCount; b++) {
                int j = b * 6;
                float delta = faces[j + 1] - faces[i + 1];
                if (delta > 0 && delta < distance
                    && faces[i] == faces[j]
                    && faces[i + 2] == faces[j + 2]
                    && faces[i + 3] == faces[j + 3]
                    && faces[i + 5] == faces[j + 5]) {
                    partner = j;
                    distance = delta;
                }
            }
            if (partner < 0) continue;
            if (used == result.length) return all;
            System.arraycopy(faces, i, result, used, 6);
            result[used + 4] = faces[partner + 1];
            for (int axis = 0; axis < 3; axis++) {
                covered[axis] = Math.min(covered[axis], result[used + axis]);
                covered[axis + 3] = Math.max(covered[axis + 3], result[used + axis + 3]);
            }
            used += 6;
        }
        return used > 0 && Arrays.equals(all, covered) ? Arrays.copyOf(result, used) : all;
    }

    private static boolean volume(float[] box) {
        return box[0] < box[3] && box[1] < box[4] && box[2] < box[5];
    }
}
