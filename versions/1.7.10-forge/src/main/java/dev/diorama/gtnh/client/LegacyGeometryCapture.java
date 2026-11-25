package dev.diorama.gtnh.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IBlockAccess;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.LegacyGeometryMath;
import dev.diorama.gtnh.capture.TerrainMetrics;

/**
 * Passive renderer observer. Worker support is opt-in and limited to audited world-access
 * identities. Only detached local vertices cross threads; texture and Looks publication stay
 * on the client owner thread. Epoch checks fence world/resource changes.
 */
public final class LegacyGeometryCapture {

    private static final boolean ENABLED = Boolean.getBoolean("diorama.experimentalGeometry");
    private static final boolean WORKERS = Boolean.getBoolean("diorama.experimentalGeometryWorkers");
    private static final int MAX_VERTICES = 256;
    private static final int MAX_STATES = 512;
    private static final String ANGELICA_SLICE = "com.gtnewhorizons.angelica.rendering.celeritas.world.WorldSlice";
    private static final Map<Long, float[]> SAMPLES = new ConcurrentHashMap<>();
    private static final ThreadLocal<LegacyGeometrySample> CAPTURE = new ThreadLocal<>();
    private static final Map<Class<?>, Method> WORLD_METHODS = new ConcurrentHashMap<>();
    private static volatile @Nullable Thread owner;
    private static volatile @Nullable Object epoch;
    private static volatile @Nullable WorldClient world;
    private static volatile @Nullable Field chunkWorld;
    private static boolean changed;

    private LegacyGeometryCapture() {}

    static synchronized void reset(@Nullable WorldClient expected) {
        if (!ENABLED) return;
        clear();
        world = expected;
        owner = Thread.currentThread();
        epoch = new Object();
    }

    static synchronized void clear() {
        epoch = null;
        world = null;
        owner = null;
        changed = false;
        SAMPLES.clear();
        CAPTURE.remove();
    }

    private static long key(int rawId, int metadata) {
        return ((long) rawId << 16) | (metadata & 65535L);
    }

    static float @Nullable [] sample(int rawId, int metadata) {
        if (!ENABLED || owner != Thread.currentThread()) return null;
        return SAMPLES.get(key(rawId, metadata));
    }

    /** Called by the tick pipeline, not the injected renderer hook. */
    static synchronized boolean consumeChanged() {
        if (!ENABLED || owner != Thread.currentThread() || !changed) return false;
        changed = false;
        return true;
    }

    static int sampleCount() {
        return SAMPLES.size();
    }

    private static boolean eligibleThread() {
        return ENABLED && epoch != null && (owner == Thread.currentThread() || WORKERS);
    }

    private static boolean sameWorld(IBlockAccess access, WorldClient expected) {
        if (access == expected) return true;
        try {
            if (access.getClass() == ChunkCache.class) {
                Field field = chunkWorld;
                if (field == null) {
                    try {
                        field = ChunkCache.class.getDeclaredField("worldObj");
                    } catch (NoSuchFieldException mapped) {
                        field = ChunkCache.class.getDeclaredField("field_72815_e");
                    }
                    field.setAccessible(true);
                    chunkWorld = field;
                }
                return field.get(access) == expected;
            }
            Class<?> type = access.getClass();
            if (!ANGELICA_SLICE.equals(type.getName())) return false;
            Method method = WORLD_METHODS.get(type);
            if (method == null) {
                method = type.getMethod("getWorld");
                WORLD_METHODS.put(type, method);
            }
            return method.invoke(access) == expected;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return false;
        }
    }

    public static void begin(Block block, @Nullable IBlockAccess access, int x, int y, int z) {
        if (!eligibleThread()) return;
        LegacyGeometrySample capture = CAPTURE.get();
        if (capture != null) capture.active = false;
        Object token = epoch;
        WorldClient expected = world;
        if (token == null || expected == null || access == null || SAMPLES.size() >= MAX_STATES) return;
        try {
            int renderType = block.getRenderType();
            if (renderType < 42 && renderType != 11 && renderType != 18 && renderType != 32) return;
            int rawId = Block.getIdFromBlock(block), metadata = access.getBlockMetadata(x, y, z);
            if (rawId < 0 || metadata < 0 || metadata > 65535) return;
            long state = key(rawId, metadata);
            if (SAMPLES.containsKey(state) || !sameWorld(access, expected) || epoch != token) return;
            if (capture == null) {
                capture = new LegacyGeometrySample(MAX_VERTICES);
                CAPTURE.set(capture);
            }
            capture.token = token;
            capture.key = state;
            capture.x = x;
            capture.y = y;
            capture.z = z;
            capture.count = 0;
            capture.directUv = false;
            capture.explicitUv = false;
            capture.active = true;
        } catch (RuntimeException | LinkageError unsupported) {
            if (capture != null) capture.active = false;
        }
    }

    private static @Nullable LegacyGeometrySample current() {
        if (!eligibleThread()) return null;
        LegacyGeometrySample capture = CAPTURE.get();
        return capture != null && capture.active && capture.token == epoch ? capture : null;
    }

    /** Observe setTextureUV/addVertex as well as addVertexWithUV without double-counting. */
    public static void texture(double u, double v) {
        LegacyGeometrySample capture = current();
        if (capture == null) return;
        capture.u = u;
        capture.v = v;
        capture.directUv = true;
    }

    public static void vertexWithUv(double x, double y, double z, double u, double v) {
        LegacyGeometrySample capture = current();
        if (capture == null) return;
        capture.explicitUv = true;
        vertex(capture, x, y, z, u, v);
    }

    public static void vertexWithUvEnd() {
        LegacyGeometrySample capture = current();
        if (capture != null) capture.explicitUv = false;
    }

    public static void directVertex(double x, double y, double z) {
        LegacyGeometrySample capture = current();
        if (capture == null || capture.explicitUv) return;
        if (!capture.directUv) {
            capture.active = false;
            return;
        }
        vertex(capture, x, y, z, capture.u, capture.v);
    }

    private static void vertex(LegacyGeometrySample capture, double x, double y, double z, double u, double v) {
        x -= capture.x;
        y -= capture.y;
        z -= capture.z;
        if (capture.count == MAX_VERTICES || !local(x) || !local(y) || !local(z) || !local(u) || !local(v)) {
            capture.active = false;
            return;
        }
        int i = capture.count++ * 5;
        capture.vertices[i] = quantize(x);
        capture.vertices[i + 1] = quantize(y);
        capture.vertices[i + 2] = quantize(z);
        capture.vertices[i + 3] = (float) Math.max(0, Math.min(1, u));
        capture.vertices[i + 4] = (float) Math.max(0, Math.min(1, v));
    }

    private static boolean local(double coordinate) {
        return Double.isFinite(coordinate) && coordinate >= -0.001 && coordinate <= 1.001;
    }

    private static float quantize(double coordinate) {
        return Math.max(0, Math.min(32, Math.round(coordinate * 32))) / 32F;
    }

    public static void end(Block block, int x, int y, int z, boolean rendered) {
        LegacyGeometrySample capture = current();
        if (capture == null) return;
        capture.active = false;
        if (!rendered || x != capture.x
            || y != capture.y
            || z != capture.z
            || capture.count < 8
            || capture.count % 4 != 0
            || Block.getIdFromBlock(block) != (int) (capture.key >>> 16)) return;
        float[] sample = Arrays.copyOf(capture.vertices, capture.count * 5);
        if (LegacyGeometryMath.bounds(sample) != null) publish(capture, sample);
    }

    private static synchronized void publish(LegacyGeometrySample capture, float[] sample) {
        if (capture.token != epoch || SAMPLES.size() >= MAX_STATES || SAMPLES.putIfAbsent(capture.key, sample) != null)
            return;
        changed = true;
        if (TerrainMetrics.ENABLED) {
            TerrainMetrics.add(TerrainMetrics.Counter.GEOMETRY_OBSERVED, 1);
            if (Thread.currentThread() != owner) TerrainMetrics.add(TerrainMetrics.Counter.GEOMETRY_WORKER_OBSERVED, 1);
        }
    }

}
