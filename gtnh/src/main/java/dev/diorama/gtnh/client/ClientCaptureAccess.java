package dev.diorama.gtnh.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.jspecify.annotations.Nullable;

import cpw.mods.fml.common.Loader;
import dev.diorama.gtnh.capture.ChunkCapture;

/**
 * Synchronous main-thread binding for the audited vanilla client provider and 12-bit section layout.
 * Extended block storage is unsupported rather than silently truncated. Live wrappers are for
 * immediate ChunkCapture consumption only; they must never be published to a worker or retained.
 */
public final class ClientCaptureAccess implements ChunkCapture.Access {

    private final Minecraft minecraft;
    private final WorldClient world;
    private final ChunkProviderClient chunkProvider;
    private final WorldProvider dimensionProvider;
    private final Object session;
    private final BooleanSupplier generationCurrent;
    private final int airBlockId;

    public ClientCaptureAccess(WorldClient expectedWorld, Object session, BooleanSupplier generationCurrent) {
        this.minecraft = Minecraft.getMinecraft();
        if (!this.onThread()) throw new IllegalStateException("Client capture requires the Minecraft main thread");
        // These mods replace the storage contract itself (and EndlessIDs may deliberately crash
        // vanilla array getters). Do not probe their arrays or infer support from a virtual AIR ID.
        if (Loader.isModLoaded("neid") || Loader.isModLoaded("endlessids")) {
            throw new UnsupportedOperationException(
                "Extended block storage requires a separately audited typed adapter");
        }
        this.world = Objects.requireNonNull(expectedWorld, "expectedWorld");
        this.session = Objects.requireNonNull(session, "session");
        this.generationCurrent = Objects.requireNonNull(generationCurrent, "generationCurrent");
        if (this.minecraft.theWorld != this.world || !this.generationCurrent.getAsBoolean()) {
            throw new IllegalStateException("Client capture world or resource generation changed");
        }
        IChunkProvider provider = this.world.getChunkProvider();
        // chunkExists is not a loaded check: vanilla ChunkProviderClient returns true everywhere.
        // Unknown provider subclasses may implement provideChunk with loading side effects.
        if (provider == null || provider.getClass() != ChunkProviderClient.class) {
            throw new UnsupportedOperationException("Unsupported client provider; refusing a possibly loading lookup");
        }
        this.chunkProvider = (ChunkProviderClient) provider;
        this.dimensionProvider = Objects.requireNonNull(this.world.provider, "dimensionProvider");
        if (this.world.getHeight() != 256) {
            throw new UnsupportedOperationException("Expected the complete client world section span 0..256");
        }
        this.airBlockId = Block.getIdFromBlock(Blocks.air);
        if (this.airBlockId < 0 || this.airBlockId > 4095) {
            throw new UnsupportedOperationException("Air registry ID does not fit the audited vanilla section layout");
        }
        this.requireCurrent();
    }

    @Override
    public boolean onThread() {
        return this.minecraft.func_152345_ab();
    }

    @Override
    public boolean current() {
        return this.onThread() && this.minecraft.theWorld == this.world
            && this.world.getChunkProvider() == this.chunkProvider
            && this.world.provider == this.dimensionProvider
            && this.generationCurrent.getAsBoolean();
    }

    @Override
    public Object session() {
        return this.session;
    }

    private void requireCurrent() {
        if (!this.onThread()) throw new IllegalStateException("Client capture requires the Minecraft main thread");
        if (!this.current())
            throw new IllegalStateException("Client capture world, provider or resource generation changed");
    }

    @Override
    public ChunkCapture.@Nullable Chunk loaded(int chunkX, int chunkZ) {
        this.requireCurrent();
        net.minecraft.world.chunk.Chunk chunk = this.chunkProvider.provideChunk(chunkX, chunkZ);
        // Do not test ordinary chunk contents for emptiness: a loaded all-air chunk is known data.
        if (chunk == null || chunk instanceof EmptyChunk || !chunk.isChunkLoaded) return null;
        // A newly allocated client chunk can precede its complete packet fill. Do not expose its
        // temporary null sections as known air; a fully received all-air chunk sets both flags too.
        if (!chunk.isTerrainPopulated || !chunk.isLightPopulated) return null;
        if (chunk.worldObj != this.world || chunk.xPosition != chunkX || chunk.zPosition != chunkZ) {
            throw new IllegalStateException("Loaded client chunk belongs to another world or coordinate");
        }
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (sections == null || sections.length != 16) {
            throw new UnsupportedOperationException("Unsupported client chunk section span");
        }
        boolean hasSkyLight = !this.dimensionProvider.hasNoSky;
        for (int i = 0; i < sections.length; i++) {
            ExtendedBlockStorage section = sections[i];
            if (section == null) continue;
            if (section.getClass() != ExtendedBlockStorage.class || section.getYLocation() != (i << 4)) {
                throw new UnsupportedOperationException("Unsupported client section representation or origin");
            }
            byte[] low = section.getBlockLSBArray();
            if (low == null || low.length != 4096) {
                throw new UnsupportedOperationException("Unsupported block-ID low-byte representation");
            }
            nibble(section.getBlockMSBArray(), false);
            nibble(section.getMetadataArray(), true);
            nibble(section.getBlocklightArray(), true);
            nibble(section.getSkylightArray(), hasSkyLight);
        }
        return new LiveChunk(this, chunk, sections, hasSkyLight);
    }

    private static void nibble(@Nullable NibbleArray values, boolean required) {
        if (values == null) {
            if (required) throw new UnsupportedOperationException("Missing required vanilla nibble storage");
        } else if (values.data == null || values.data.length != 2048) {
            throw new UnsupportedOperationException("Unsupported extended nibble storage");
        }
    }

    private static int nibble(NibbleArray values, int index) {
        return (values.data[index >> 1] >>> ((index & 1) << 2)) & 15;
    }

    private static final class LiveChunk implements ChunkCapture.Chunk {

        private final ClientCaptureAccess access;
        private final net.minecraft.world.chunk.Chunk chunk;
        private final ExtendedBlockStorage[] sections;
        private final boolean hasSkyLight;

        private LiveChunk(ClientCaptureAccess access, net.minecraft.world.chunk.Chunk chunk,
            ExtendedBlockStorage[] sections, boolean hasSkyLight) {
            this.access = access;
            this.chunk = chunk;
            this.sections = sections;
            this.hasSkyLight = hasSkyLight;
        }

        @Override
        public int chunkX() {
            this.access.requireCurrent();
            return this.chunk.xPosition;
        }

        @Override
        public int chunkZ() {
            this.access.requireCurrent();
            return this.chunk.zPosition;
        }

        @Override
        public int minSectionY() {
            this.access.requireCurrent();
            return 0;
        }

        @Override
        public int sectionCount() {
            this.access.requireCurrent();
            return 16;
        }

        @Override
        public boolean hasSkyLight() {
            this.access.requireCurrent();
            return this.hasSkyLight;
        }

        @Override
        public ChunkCapture.@Nullable Section section(int index) {
            this.check(index, 0);
            ExtendedBlockStorage storage = this.sections[index];
            return storage == null ? null : new LiveSection(this, storage);
        }

        private void check(int sectionIndex, int cell) {
            this.access.requireCurrent();
            if (sectionIndex < 0 || sectionIndex >= 16 || cell < 0 || cell >= 4096) {
                throw new IndexOutOfBoundsException("Client section or voxel index");
            }
        }

        @Override
        public int blockLight(int sectionIndex, int cell) {
            this.check(sectionIndex, cell);
            return this.chunk
                .getSavedLightValue(EnumSkyBlock.Block, cell & 15, (sectionIndex << 4) | (cell >> 8), (cell >> 4) & 15);
        }

        @Override
        public int skyLight(int sectionIndex, int cell) {
            this.check(sectionIndex, cell);
            if (!this.hasSkyLight) return 0;
            // Vanilla empty-section light is height-map dependent, not the modern nearest-layer
            // fallback. Query this already-loaded chunk directly, including its null sections.
            return this.chunk
                .getSavedLightValue(EnumSkyBlock.Sky, cell & 15, (sectionIndex << 4) | (cell >> 8), (cell >> 4) & 15);
        }
    }

    private static final class LiveSection implements ChunkCapture.Section {

        private final LiveChunk owner;
        private final ExtendedBlockStorage section;

        private LiveSection(LiveChunk owner, ExtendedBlockStorage section) {
            this.owner = owner;
            this.section = section;
        }

        private void check(int index) {
            this.owner.access.requireCurrent();
            if (index < 0 || index >= 4096) throw new IndexOutOfBoundsException("Client voxel index");
        }

        @Override
        public int blockId(int index) {
            this.check(index);
            int raw = this.section.getBlockLSBArray()[index] & 255;
            NibbleArray add = this.section.getBlockMSBArray();
            if (add != null) raw |= nibble(add, index) << 8;
            int observed = Block
                .getIdFromBlock(this.section.getBlockByExtId(index & 15, index >> 8, (index >> 4) & 15));
            // Never normalize an unregistered raw ID to the air returned by the vanilla registry
            // fallback. A distinct non-air virtual ID instead reveals incompatible extended storage.
            if (observed < 0 || observed > 4095 || (observed != raw && observed != this.owner.access.airBlockId)) {
                throw new UnsupportedOperationException("Virtual block ID differs from audited vanilla raw storage");
            }
            return raw;
        }

        @Override
        public int metadata(int index) {
            this.check(index);
            int raw = nibble(this.section.getMetadataArray(), index);
            if (this.section.getExtBlockMetadata(index & 15, index >> 8, (index >> 4) & 15) != raw) {
                throw new UnsupportedOperationException("Virtual metadata differs from audited vanilla nibble storage");
            }
            return raw;
        }
    }
}
