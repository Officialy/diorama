package dev.diorama.gtnh.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
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
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.SectionBlocks;
import dev.diorama.gtnh.capture.TintCapture;

/**
 * Synchronous main-thread binding for the vanilla client provider and audited section storage.
 * Optional EndlessIDs access is isolated from the vanilla array path. Live wrappers are for
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
    private final boolean endlessLoaded;
    private final boolean extended;
    private final boolean extendedBiomes;

    public ClientCaptureAccess(WorldClient expectedWorld, Object session, BooleanSupplier generationCurrent) {
        this.minecraft = Minecraft.getMinecraft();
        if (!this.onThread()) throw new IllegalStateException("Client capture requires the Minecraft main thread");
        if (Loader.isModLoaded("neid")) {
            throw new UnsupportedOperationException("NEID storage requires its own typed adapter");
        }
        this.endlessLoaded = Loader.isModLoaded("endlessids");
        this.extended = this.endlessLoaded && EndlessSectionStorage.enabled();
        this.extendedBiomes = this.endlessLoaded && EndlessBiomeStorage.enabled();
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
        if (this.airBlockId < 0 || this.airBlockId > (this.extended ? 0xFFFFFF : 4095)) {
            throw new UnsupportedOperationException("Air registry ID does not fit the selected section layout");
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
        net.minecraft.world.chunk.Chunk chunk = this.loadedChunk(chunkX, chunkZ);
        if (chunk == null) return null;
        return this.wrap(chunk);
    }

    int rawAirId() {
        this.requireCurrent();
        return this.airBlockId;
    }

    int snowContext(int rawId) {
        this.requireCurrent();
        if (rawId < 0) return GrassContext.UNKNOWN;
        Block block = Block.getBlockById(rawId);
        if (block == null || Block.getIdFromBlock(block) != rawId) return GrassContext.UNKNOWN;
        Material material = block.getMaterial();
        this.requireCurrent();
        return material == Material.snow || material == Material.craftedSnow ? GrassContext.SNOWY : GrassContext.NORMAL;
    }

    /** Copies only already-received biome columns; never resolves or writes unknown biome cells. */
    public int @Nullable [] biomes(int chunkX, int chunkZ) {
        net.minecraft.world.chunk.Chunk chunk = this.loadedChunk(chunkX, chunkZ);
        if (chunk == null) return null;
        if (chunk.getClass() != net.minecraft.world.chunk.Chunk.class)
            throw new UnsupportedOperationException("Unsupported client biome chunk representation");
        int[] copied = this.endlessLoaded ? EndlessBiomeStorage.read(chunk, this.extendedBiomes)
            : TintCapture.biomes(chunk.getBiomeArray());
        this.requireCurrent();
        return copied;
    }

    private net.minecraft.world.chunk.@Nullable Chunk loadedChunk(int chunkX, int chunkZ) {
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
        this.requireCurrent();
        return chunk;
    }

    private ChunkCapture.Chunk wrap(net.minecraft.world.chunk.Chunk chunk) {
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        if (sections == null || sections.length != 16) {
            throw new UnsupportedOperationException("Unsupported client chunk section span");
        }
        boolean hasSkyLight = !this.dimensionProvider.hasNoSky;
        SectionBlocks[] blocks = new SectionBlocks[sections.length];
        for (int i = 0; i < sections.length; i++) {
            ExtendedBlockStorage section = sections[i];
            if (section == null) continue;
            blocks[i] = this.blockData(section, i);
            nibble(section.getBlocklightArray(), true);
            nibble(section.getSkylightArray(), hasSkyLight);
        }
        return new LiveChunk(this, chunk, sections, blocks, hasSkyLight);
    }

    private SectionBlocks blockData(ExtendedBlockStorage section, int index) {
        this.requireCurrent();
        if (section.getClass() != ExtendedBlockStorage.class || section.getYLocation() != (index << 4))
            throw new UnsupportedOperationException("Unsupported client section representation or origin");
        if (this.endlessLoaded) EndlessSectionStorage.check(section, this.extended);
        SectionBlocks blocks = this.extended ? EndlessSectionStorage.read(section)
            : SectionBlocks.vanilla(
                section.getBlockLSBArray(),
                data(section.getBlockMSBArray()),
                data(section.getMetadataArray()));
        this.requireCurrent();
        return blocks;
    }

    private int rawId(ExtendedBlockStorage section, SectionBlocks blocks, int index) {
        this.requireCurrent();
        int raw = blocks.blockId(index);
        if (this.extended) {
            if (EndlessSectionStorage.blockId(section, index) != raw)
                throw new UnsupportedOperationException("EndlessIDs raw ID differs from audited storage");
        } else {
            int observed = Block.getIdFromBlock(section.getBlockByExtId(index & 15, index >> 8, (index >> 4) & 15));
            // Preserve unknown raw IDs instead of the registry's air fallback.
            if (observed < 0 || observed > 4095 || (observed != raw && observed != this.airBlockId))
                throw new UnsupportedOperationException("Virtual block ID differs from audited vanilla raw storage");
        }
        this.requireCurrent();
        return raw;
    }

    private static void nibble(@Nullable NibbleArray values, boolean required) {
        if (values == null) {
            if (required) throw new UnsupportedOperationException("Missing required vanilla nibble storage");
        } else if (values.data == null || values.data.length != 2048) {
            throw new UnsupportedOperationException("Unsupported extended nibble storage");
        }
    }

    private static byte @Nullable [] data(@Nullable NibbleArray values) {
        return values == null ? null : values.data;
    }

    private static final class LiveChunk implements ChunkCapture.Chunk {

        private final ClientCaptureAccess access;
        private final net.minecraft.world.chunk.Chunk chunk;
        private final ExtendedBlockStorage[] sections;
        private final SectionBlocks[] blocks;
        private final boolean hasSkyLight;

        private LiveChunk(ClientCaptureAccess access, net.minecraft.world.chunk.Chunk chunk,
            ExtendedBlockStorage[] sections, SectionBlocks[] blocks, boolean hasSkyLight) {
            this.access = access;
            this.chunk = chunk;
            this.sections = sections;
            this.blocks = blocks;
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
            return storage == null ? null : new LiveSection(this, storage, this.blocks[index]);
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
        private final SectionBlocks blocks;

        private LiveSection(LiveChunk owner, ExtendedBlockStorage section, SectionBlocks blocks) {
            this.owner = owner;
            this.section = section;
            this.blocks = blocks;
        }

        private void check(int index) {
            this.owner.access.requireCurrent();
            if (index < 0 || index >= 4096) throw new IndexOutOfBoundsException("Client voxel index");
        }

        @Override
        public int blockId(int index) {
            this.check(index);
            return this.owner.access.rawId(this.section, this.blocks, index);
        }

        @Override
        public int metadata(int index) {
            this.check(index);
            int raw = this.blocks.metadata(index);
            int observed = this.owner.access.extended ? EndlessSectionStorage.metadata(this.section, index)
                : this.section.getExtBlockMetadata(index & 15, index >> 8, (index >> 4) & 15);
            if (observed != raw) {
                throw new UnsupportedOperationException("Virtual metadata differs from audited raw storage");
            }
            return raw;
        }
    }
}
