package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import java.util.Arrays;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/** Modern snapshot and block-state numbering adapter for the job-local numeric reader. */
public final class VoxelReader extends VoxelReaderCore {
	public static final int UNKNOWN = VoxelReaderCore.UNKNOWN;
	static final int AIR_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
	private static final DecodedSections.Air AIR = new DecodedSections.Air(AIR_ID);
	static final boolean DEBUG = dev.diorama.Diorama.DEBUG;

	private final ChunkSource source;
	private final @Nullable Looks looks;

	VoxelReader(ChunkSource source) {
		this(source, null);
	}

	public VoxelReader(ChunkSource source, @Nullable Looks looks) {
		this(source, looks, false);
	}

	/** With {@code shapes}, {@link #solid} tells blocks smaller than a cube apart. */
	public VoxelReader(ChunkSource source, @Nullable Looks looks, boolean shapes) {
		super(source.minY(), source.maxY(), AIR_ID, looks == null ? null : looks.kind, looks == null || !shapes ? null : looks.shape, DEBUG);
		this.source = source;
		this.looks = looks;
	}

	@Override
	protected @Nullable DecodedSections loadChunk(int chunkX, int chunkZ) {
		ChunkSnapshot snapshot = this.source.chunk(chunkX, chunkZ);
		return snapshot == null ? null : new Decoded(snapshot, this.looks);
	}

	@Override
	protected int readBlockLight(DecodedSections chunk, int sectionIndex, int x, int y, int z) {
		return ((Decoded) chunk).snapshot.blockLight(sectionIndex, x, y, z);
	}

	@Override
	protected int readSkyLight(DecodedSections chunk, int sectionIndex, int x, int y, int z) {
		return ((Decoded) chunk).snapshot.skyLight(sectionIndex, x, y, z);
	}

	/** The chunk snapshot holding this column, for biome tints; shares the numeric job cache. */
	@Nullable ChunkSnapshot snapshot(int x, int z) {
		Decoded chunk = (Decoded) this.chunk(x >> 4, z >> 4);
		return chunk == null ? null : chunk.snapshot;
	}

	private static final class Decoded extends DecodedSections {
		final ChunkSnapshot snapshot;
		final @Nullable Looks looks;

		Decoded(ChunkSnapshot snapshot, @Nullable Looks looks) {
			super(snapshot.sectionCount(), snapshot.minSectionY, AIR);
			this.snapshot = snapshot;
			this.looks = looks;
		}

		/** The state's id for a bake, with its look worked out when there are looks to read. */
		private int id(BlockState state) {
			return this.looks == null ? BlockLooks.bakeId(state) : StateLooks.bakeLookId(this.looks, state);
		}

		@Override
		protected void fillSolidByBlock(int index, byte[] kinds, int @Nullable [] shapes) {
			long[] partial = NOTHING_SOLID;
			// Straight from the blocks, without numbering every block of the section.
			PalettedContainer<BlockState> states = this.snapshot.section(index);
			long[] mask = new long[64];
			BlockState last = null;
			int f = 0;
			for (int i = 0; i < 4096; i++) {
				BlockState state = this.snapshot.state(states, i & 15, i >> 8, i >> 4 & 15);
				if (state != last) {
					last = state;
					f = solidFlags(this.id(state), kinds, shapes);
				}
				mask[i >> 6] |= (long) (f & 1) << i;
				if (f > 1) {
					if (partial == NOTHING_SOLID) {
						partial = new long[64];
					}
					partial[i >> 6] |= 1L << i;
				}
			}
			this.partial[index] = partial;
			this.solid[index] = mask;
		}

		@Override
		protected Section read(int index) {
			PalettedContainer<BlockState> states = this.snapshot.section(index);
			BlockState uniform = this.snapshot.uniformSection(index);
			if (states == null && uniform == null) {
				return this.air.section;
			}
			ChunkSnapshot.SectionCopycats copycats = this.looks == null ? null : this.snapshot.copycats(index);
			if (copycats == null) {
				if (uniform != null) {
					return new Section(this.id(this.snapshot.turned(uniform)), null, null, null, null);
				}
				ChunkSnapshot.Packed packed = this.snapshot.packed(index);
				int[] palette = packed == null ? null : this.paletteIds(packed);
				return palette != null ? new Section(-1, null, packed.storage()::unpack, palette, this.snapshot.columns()) : BY_BLOCK;
			}
			// Filled copycats take their material's look, so their sections are numbered block by
			// block up front: the solid bits come from these ids too.
			int[] ids;
			if (uniform != null) {
				ids = new int[4096];
				Arrays.fill(ids, this.id(this.snapshot.turned(uniform)));
			} else {
				ids = this.byBlock(index);
			}
			for (int i = 0; i < copycats.at().length; i++) {
				int at = copycats.at()[i];
				ids[at] = this.looks.copycat(ids[at], Block.BLOCK_STATE_REGISTRY.getId(copycats.material()[i]));
			}
			return new Section(-1, ids, null, null, null);
		}

		/** Every block's id in a section with a container, looked up block by block. */
		@Override
		protected int[] byBlock(int index) {
			PalettedContainer<BlockState> states = this.snapshot.section(index);
			int[] ids = new int[4096];
			BlockState lastState = null;
			int lastId = 0;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = this.snapshot.state(states, x, y, z);
						if (state != lastState) {
							lastState = state;
							lastId = this.id(state);
						}
						ids[(y << 8) | (z << 4) | x] = lastId;
					}
				}
			}
			return ids;
		}

		/**
		 * The id of each of the section's palette entries, as this view has them, with room for
		 * every index its storage can hold; null if the palette is too big to number this way.
		 */
		private int @Nullable [] paletteIds(ChunkSnapshot.Packed packed) {
			int bits = packed.storage().getBits();
			int size = packed.palette().getSize();
			if (bits > 8 || size > 1 << bits) {
				return null;
			}
			int[] ids = new int[1 << bits];
			Arrays.fill(ids, AIR_ID);
			for (int p = 0; p < size; p++) {
				BlockState state = packed.palette().valueFor(p);
				if (state != null) {
					ids[p] = this.id(this.snapshot.turned(state));
				}
			}
			return ids;
		}
	}
}
