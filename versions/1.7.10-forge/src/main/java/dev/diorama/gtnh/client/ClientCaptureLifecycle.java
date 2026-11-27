package dev.diorama.gtnh.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.IChunkProvider;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.Looks;
import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.EpochFence;
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.TintCapture;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Early resource hooks and owner-thread capture/acceptance gate. No worker, texture upload, disk
 * writes or block reads are scheduled here. A hook exception without RETURN stays closed until
 * restart, including a vanilla default-resource-pack retry. Session tokens contain no live objects.
 */
public final class ClientCaptureLifecycle {

    // Initialized at the first early client hook, not reset at mod init or on the next tick.
    private static final EpochFence FENCE = new EpochFence();
    private static @Nullable WorldClient world;
    private static @Nullable WorldClient unloading;
    private static @Nullable WorldProvider provider;
    private static @Nullable IChunkProvider chunks;
    private static int dimension;
    private static boolean noSky;
    private static boolean atlasPost;
    private static @Nullable Object completedManager;
    private static @Nullable TextureMap completedAtlas;
    private static @Nullable TextureMap lastCompletedAtlas;
    private static @Nullable Session active;

    private ClientCaptureLifecycle() {}

    private static Minecraft client() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.func_152345_ab())
            throw new IllegalStateException("Capture lifecycle requires the Minecraft main thread");
        return mc;
    }

    private static void revoke() {
        if (active != null) {
            active.appearance.looks()
                .invalidate();
            active = null;
        }
        LegacyGeometryCapture.clear();
    }

    public static void beginReload(SimpleReloadableResourceManager manager) {
        Minecraft mc = client();
        if (manager != mc.getResourceManager()) return;
        FENCE.beginReload();
        revoke();
        completedManager = null;
        completedAtlas = null;
        lastCompletedAtlas = null;
        atlasPost = false;
    }

    public static void endReload(SimpleReloadableResourceManager manager) {
        if (manager != client().getResourceManager()) return;
        FENCE.endReload();
        completedManager = manager;
        completedAtlas = lastCompletedAtlas;
    }

    /** Public single-pack mutations are not a complete reload and cannot grant readiness. */
    public static void resourcePackChanged(SimpleReloadableResourceManager manager) {
        if (manager != client().getResourceManager()) return;
        FENCE.invalidateResources();
        revoke();
        completedManager = null;
        completedAtlas = null;
        lastCompletedAtlas = null;
    }

    public static void beginAtlas(TextureMap atlas) {
        if (atlas != client().getTextureMapBlocks()) return;
        FENCE.beginAtlas();
        revoke();
        completedManager = null;
        completedAtlas = null;
        lastCompletedAtlas = null;
        atlasPost = false;
    }

    /** Called after Forge's Post callbacks and fluid-icon updates, not from an ordinary listener. */
    public static void atlasPosted(TextureMap atlas) {
        if (atlas != client().getTextureMapBlocks()) return;
        atlasPost = true;
    }

    public static void endAtlas(TextureMap atlas) {
        if (atlas != client().getTextureMapBlocks()) return;
        if (!atlasPost) FENCE.failClosed();
        FENCE.endAtlas();
        if (atlasPost) lastCompletedAtlas = atlas;
    }

    /** Unload fires while Minecraft still holds the old world. Do not rebind it in that callback. */
    public static void unloaded(WorldClient old) {
        Minecraft mc = client();
        if (old != world && old != mc.theWorld) return;
        unloading = old;
        revoke();
        FENCE.worldChanged(false);
        world = null;
        provider = null;
        chunks = null;
    }

    /** Called only at END tick; observes identities, never loads chunks or prepares block looks. */
    public static void observeWorld() {
        Minecraft mc = client();
        WorldClient next = mc.theWorld;
        if (next == unloading) next = null;
        else unloading = null;
        WorldProvider nextProvider = next == null ? null : next.provider;
        IChunkProvider nextChunks = next == null ? null : next.getChunkProvider();
        int nextDimension = nextProvider == null ? 0 : nextProvider.dimensionId;
        boolean nextNoSky = nextProvider != null && nextProvider.hasNoSky;
        if (next != world || nextProvider != provider
            || nextChunks != chunks
            || nextDimension != dimension
            || nextNoSky != noSky) {
            revoke();
            FENCE.worldChanged(next != null && nextProvider != null && nextChunks != null);
            world = next;
            provider = nextProvider;
            chunks = nextChunks;
            dimension = nextDimension;
            noSky = nextNoSky;
        }
    }

    /** Every capture/accept checks actual identities itself; a previous tick observation is not enough. */
    private static boolean current(@Nullable Object token) {
        Minecraft mc = client();
        // A revoked token during an ordinary reload is pending, not an identity fault.
        if (!FENCE.current(token)) return false;
        if (world == null || mc.theWorld != world
            || world == unloading
            || world.provider != provider
            || world.getChunkProvider() != chunks
            || provider == null
            || provider.dimensionId != dimension
            || provider.hasNoSky != noSky) {
            revoke();
            FENCE.worldChanged(false);
            return false;
        }
        if (completedManager != mc.getResourceManager() || completedAtlas != mc.getTextureMapBlocks()
            || mc.getResourceManager()
                .getClass() != SimpleReloadableResourceManager.class
            || mc.getTextureMapBlocks()
                .getClass() != TextureMap.class) {
            revoke();
            FENCE.failClosed();
            return false;
        }
        return FENCE.current(token);
    }

    /** Explicit callers only. No session is created during a reload, an unload callback, or a worker. */
    public static @Nullable Session openSession(int appearanceCapacity) {
        client();
        // Only observeWorld at the declared safe boundary may install a new world identity.
        Object token = FENCE.session();
        if (token == null || !current(token)) return null;
        if (active != null) return active;
        if (world == null) return null;
        ClientBlockAppearance appearance = new ClientBlockAppearance(token, appearanceCapacity, () -> current(token));
        ClientCaptureAccess access = new ClientCaptureAccess(world, token, () -> current(token));
        if (!current(token)) {
            appearance.looks()
                .invalidate();
            return null;
        }
        active = new Session(token, appearance, access);
        LegacyGeometryCapture.reset(world);
        return active;
    }

    /** Predicate immediately before a nonreentrant main-thread merge; no upload/commit is done here. */
    public static boolean accepts(@Nullable Object token, Looks domain) {
        client();
        return current(token) && active != null && active.token == token && active.appearance.looks() == domain;
    }

    public static boolean accepts(ChunkCapture.Result result) {
        client();
        return result.status() == ChunkCapture.Status.READY && result.snapshot() != null
            && result.snapshot()
                .lookDomain() instanceof Looks domain
            && accepts(result.session(), domain);
    }

    /** Local diagnostic: does not open a capture session, load chunks, or sample block renderers. */
    static List<String> diagnostics(boolean missing) {
        Minecraft mc = client();
        List<String> lines = new ArrayList<>();
        WorldClient currentWorld = mc.theWorld;
        if (currentWorld == null || currentWorld.provider == null) {
            lines.add("Diorama: no client world");
            return lines;
        }
        ClientMapDimension map = ClientMapDimension.of(currentWorld);
        lines.add(
            "Diorama: dimension " + currentWorld.provider.dimensionId
                + ", height "
                + currentWorld.getHeight()
                + ", noSky="
                + currentWorld.provider.hasNoSky
                + ", map="
                + (map == null ? "unsupported" : map.surfaceTerrain() ? "surface" : "cave"));
        lines.add(
            "Provider: " + currentWorld.provider.getClass()
                .getName());
        IChunkProvider chunkProvider = currentWorld.getChunkProvider();
        lines.add(
            "Chunks: " + (chunkProvider == null ? "none"
                : chunkProvider.getClass()
                    .getName()));
        Session session = active;
        if (session == null || !current(session.token)) {
            lines.add("No active capture session (open map or show minimap first).");
            return lines;
        }
        lines.add(
            "Appearances: " + session.looks()
                .used()
                + "/"
                + session.looks().kind.length
                + "; observed geometry: "
                + LegacyGeometryCapture.sampleCount()
                + "; unresolved sampled states: "
                + session.missing.size());
        lines.add(ClientMinimap.coverageStatus());
        if (missing) session.describeMissing(lines);
        return lines;
    }

    /** Native owner-thread handle: never send it, its access, or its appearance callbacks to workers. */
    public static final class Session implements ChunkCapture.Inputs {

        private final Object token;
        private final ClientBlockAppearance appearance;
        private final ClientCaptureAccess access;
        private final ClientBiomeColors biomes;
        private final Map<Long, Integer> missing = new HashMap<>();
        private final Long2IntOpenHashMap captureLooks = new Long2IntOpenHashMap();
        private static final int NOT_CACHED = -2;
        private static final int CONTEXTUAL_GRASS = -3;

        private Session(Object token, ClientBlockAppearance appearance, ClientCaptureAccess access) {
            this.token = token;
            this.appearance = appearance;
            this.access = access;
            this.biomes = new ClientBiomeColors(access);
            this.captureLooks.defaultReturnValue(NOT_CACHED);
        }

        public Object token() {
            return this.token;
        }

        public PreparedBlockLooks looks() {
            return this.appearance.looks();
        }

        @Override
        public int stateLimit() {
            return this.appearance.looks().kind.length;
        }

        @Override
        public boolean allowUnknownLooks() {
            return true;
        }

        @Override
        public boolean prepareState(int rawId, int metadata) {
            boolean ready = false;
            if (rawId >= 0 && rawId <= 0xFFFFFF) {
                if (this.appearance.isGrass(rawId, metadata)) {
                    boolean normal = this.appearance.prepare(rawId, metadata, GrassContext.NORMAL) >= 0;
                    boolean snowy = this.appearance.prepare(rawId, metadata, GrassContext.SNOWY) >= 0;
                    ready = normal && snowy;
                } else {
                    ready = this.appearance.prepare(rawId, metadata) >= 0;
                }
            }
            long key = ((long) rawId << 16) | (metadata & 65535L);
            if (ready) this.missing.remove(key);
            else if (this.missing.size() < 512 || this.missing.containsKey(key)) {
                int previous = this.missing.getOrDefault(key, 0);
                this.missing.put(key, previous == Integer.MAX_VALUE ? previous : previous + 1);
            }
            return ready;
        }

        private void describeMissing(List<String> lines) {
            List<Map.Entry<Long, Integer>> entries = new ArrayList<>(this.missing.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (entries.isEmpty()) lines.add("No unresolved states sampled.");
            for (int i = 0; i < Math.min(10, entries.size()); i++) {
                Map.Entry<Long, Integer> entry = entries.get(i);
                int raw = (int) (entry.getKey() >> 16), meta = (int) (entry.getKey() & 65535L);
                String name = "unregistered";
                if (raw >= 0) {
                    Block block = Block.getBlockById(raw);
                    if (block != null && Block.getIdFromBlock(block) == raw) {
                        name = String.valueOf(Block.blockRegistry.getNameForObject(block));
                    }
                }
                lines.add(name + " (" + raw + ":" + meta + "), unresolved attempts: " + entry.getValue());
            }
        }

        @Override
        public int rawAirId() {
            return this.access.rawAirId();
        }

        public ClientCaptureAccess access() {
            return this.access;
        }

        /** Explicit capture; preparation and snapshot production share one raw-state read. */
        public ChunkCapture.Result capture(int chunkX, int chunkZ) {
            this.captureLooks.clear();
            try {
                return ChunkCapture.capture(this.access, this, chunkX, chunkZ);
            } finally {
                this.captureLooks.clear();
            }
        }

        @Override
        public Object session() {
            return this.token;
        }

        @Override
        public int airId() {
            return this.appearance.looks()
                .airId();
        }

        @Override
        public int lookId(int rawId, int metadata, int x, int y, int z, int aboveRawId) {
            // ChunkCapture checks the live epoch around every callback. Looks are immutable within
            // this synchronous capture; never retain a miss across a later appearance preparation.
            long key = (((long) rawId << 16) | (metadata & 65535L)) << 2;
            int id = this.captureLooks.get(key);
            if (id == NOT_CACHED) {
                id = this.appearance.isGrass(rawId, metadata) ? CONTEXTUAL_GRASS
                    : this.appearance.find(rawId, metadata);
                this.captureLooks.put(key, id);
            }
            if (id != CONTEXTUAL_GRASS) return id;
            int context = y == 255 ? GrassContext.NORMAL : this.access.snowContext(aboveRawId);
            if (context == GrassContext.UNKNOWN) return -1;
            long contextualKey = key | context;
            id = this.captureLooks.get(contextualKey);
            if (id == NOT_CACHED) {
                id = this.appearance.find(rawId, metadata, context);
                this.captureLooks.put(contextualKey, id);
            }
            return id;
        }

        @Override
        public ChunkSnapshot.@Nullable Tints tints(int chunkX, int chunkZ, int[] surface) {
            // Vanilla grass/leaves/water use a 3x3 neighbourhood. No new user setting is introduced.
            return TintCapture.prepare(this.biomes, this.token, chunkX, chunkZ, surface, 1);
        }

        @Override
        public int fogColor(int chunkX, int chunkZ) {
            // Canonical unrecognised-biome Nether haze, not camera/weather/potion fog.
            return ChunkSnapshot.NETHER_WASTES_FOG;
        }
    }
}
