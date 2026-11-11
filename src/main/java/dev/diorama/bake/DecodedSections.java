package dev.diorama.bake;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** Job-local decoded section caches, independent of the snapshot representation. */
abstract class DecodedSections {
	/**
	 * A section whose palette can't be numbered whole (the registry itself, in a section of over
	 * 256 kinds of block), read block by block, and only as far as each lookup needs.
	 */
	static final Section BY_BLOCK = new Section(-1, null, null, null, null);
	/** The solid mask of a section with nothing solid in it. Shared and never written. */
	static final long[] NOTHING_SOLID = new long[64];
	/**
	 * The solid mask of a section of a single block that isn't solid. Shared and never written; not
	 * {@link #NOTHING_SOLID}, which {@link VoxelReader#nothingSolid} tells apart, so the GPU's table holds these
	 * sections as it always has.
	 */
	static final long[] NONE_OF_ONE = new long[64];
	/** Every bit set: a section of a single solid block. Shared and never written. */
	static final long[] ALL_SOLID = new long[64];

	static {
		Arrays.fill(ALL_SOLID, -1L);
	}

	/** One shared air domain for readers using the same block ids. */
	static final class Air {
		final int id;
		final int[] ids;
		final Section section;

		Air(int airId) {
			this.id = airId;
			this.ids = new int[4096];
			Arrays.fill(this.ids, airId);
			this.section = new Section(airId, null, null, null, null);
		}
	}

	@FunctionalInterface
	interface PackedIndices {
		void unpack(int[] out);
	}

	/**
	 * What a section holds: one block, every block's id already (copycats, and palettes too big to
	 * number whole), or the snapshot's packed palette indices with an id per palette entry.
	 */
	static final class Section {
		/** The id filling the section, or -1. */
		final int single;
		/** Every block's id, where it was decoded block by block; otherwise null. */
		final int @Nullable [] ids;
		/** Otherwise the palette index of each block, and each palette entry's id. */
		final @Nullable PackedIndices storage;
		final int @Nullable [] palette;
		/** For a turned view, where its columns are in the storage: mapped by the snapshot. */
		final int @Nullable [] columns;

		Section(int single, int @Nullable [] ids, @Nullable PackedIndices storage, int @Nullable [] palette, int @Nullable [] columns) {
			this.single = single;
			this.ids = ids;
			this.storage = storage;
			this.palette = palette;
			this.columns = columns;
		}
	}

	protected final Air air;
	final int minSectionY;
	final @Nullable Section[] sections;
	/** Per section, every block's id, indexed {@code (y << 8) | (z << 4) | x}; made when {@link VoxelReader#state} first reads it. */
	final int[][] ids;
	/** Per section, one bit per block, set where it is {@link Looks#SOLID}; built when first read. */
	final long[][] solid;
	/** The same, set where a solid block is smaller than a cube. */
	final long[][] partial;
	/** See {@link VoxelReader#casterSection}; built when first read. */
	final long[][] casters;
	/** See {@link VoxelReader#solidTop}; {@code Integer.MAX_VALUE} until worked out. */
	int solidTop = Integer.MAX_VALUE;

	DecodedSections(int count, int minSectionY, Air air) {
		this.air = air;
		this.minSectionY = minSectionY;
		this.sections = new Section[count];
		this.ids = new int[count][];
		this.solid = new long[count][];
		this.partial = new long[count][];
		this.casters = new long[count][];
	}

	protected abstract Section read(int index);

	protected abstract int[] byBlock(int index);

	/** Fills both mask caches without first allocating block ids. */
	protected abstract void fillSolidByBlock(int index, byte[] kinds, int @Nullable [] shapes);

	/** The section's solid mask, indexed as its ids. */
	final long[] solid(int index, byte[] kinds, int @Nullable [] shapes, int[] unpacked) {
		if (index < 0 || index >= this.solid.length) {
			return NOTHING_SOLID;
		}
		long[] mask = this.solid[index];
		if (mask == null) {
			long[] partial = NOTHING_SOLID;
			Section section = this.section(index);
			if (section == this.air.section) {
				mask = NOTHING_SOLID;
			} else if (section.single >= 0) {
				int id = section.single;
				mask = kinds[id] == Looks.SOLID ? ALL_SOLID : NONE_OF_ONE;
				if (mask == ALL_SOLID && shapes != null && shapes[id] != 0) {
					partial = ALL_SOLID;
				}
			} else if (section == BY_BLOCK && this.ids[index] == null) {
				this.fillSolidByBlock(index, kinds, shapes);
				return this.solid[index];
			} else {
				// Shadow and sky rays through a section need no more than these bits, so most
				// sections a bake reads are never numbered block by block. Sections with
				// copycats go through their ids, which carry the copycats' materials.
				int[] ids = section == BY_BLOCK ? this.ids[index] : section.ids;
				int[] columns = section.columns;
				// Per palette entry (per block with ids): 1 if solid, 3 if solid and smaller than a cube.
				byte[] flags;
				if (ids == null) {
					section.storage.unpack(unpacked);
					int[] palette = section.palette;
					flags = new byte[palette.length];
					for (int p = 0; p < palette.length; p++) {
						flags[p] = solidFlags(palette[p], kinds, shapes);
					}
				} else {
					columns = null;
					flags = null;
				}
				mask = new long[64];
				for (int w = 0; w < 64; w++) {
					long solid = 0;
					long smaller = 0;
					for (int b = 0; b < 64; b++) {
						int i = (w << 6) | b;
						int f = flags == null ? solidFlags(ids[i], kinds, shapes) : flags[unpacked[columns == null ? i : (i & 0xF00) | columns[i & 0xFF]]];
						solid |= (long) (f & 1) << b;
						smaller |= (long) (f >> 1) << b;
					}
					mask[w] = solid;
					if (smaller != 0) {
						if (partial == NOTHING_SOLID) {
							partial = new long[64];
						}
						partial[w] = smaller;
					}
				}
			}
			this.partial[index] = partial;
			this.solid[index] = mask;
		}
		return mask;
	}

	static byte solidFlags(int id, byte[] kinds, int @Nullable [] shapes) {
		if (kinds[id] != Looks.SOLID) {
			return 0;
		}
		return shapes != null && shapes[id] != 0 ? (byte) 3 : (byte) 1;
	}

	/** The section's mask of solid blocks smaller than a cube; read after {@link #solid}. */
	final long[] partial(int index) {
		return index < 0 || index >= this.partial.length || this.partial[index] == null ? NOTHING_SOLID : this.partial[index];
	}

	final int solidTop(byte[] kinds, int @Nullable [] shapes, int[] unpacked) {
		if (this.solidTop == Integer.MAX_VALUE) {
			int top = Integer.MIN_VALUE;
			for (int index = this.sections.length - 1; index >= 0 && top == Integer.MIN_VALUE; index--) {
				long[] mask = this.solid(index, kinds, shapes, unpacked);
				for (int word = 63; word >= 0; word--) {
					if (mask[word] != 0) {
						// Bits run x, then z, then y: 64 bits are a quarter of one y layer.
						int highest = (word << 6) + 63 - Long.numberOfLeadingZeros(mask[word]);
						top = ((this.minSectionY + index) << 4) + (highest >> 8) + 1;
						break;
					}
				}
			}
			this.solidTop = top;
		}
		return this.solidTop;
	}

	/**
	 * The section's ids, decoded once for this job: a ray stays in a section for many steps, and
	 * an array read is the fastest lookup.
	 */
	final int[] ids(int index, int[] unpacked) {
		if (index < 0 || index >= this.ids.length) {
			return this.air.ids;
		}
		int[] ids = this.ids[index];
		if (ids == null) {
			Section section = this.section(index);
			if (section == this.air.section) {
				ids = this.air.ids;
			} else if (section.ids != null) {
				ids = section.ids;
			} else if (section == BY_BLOCK) {
				ids = this.byBlock(index);
			} else if (section.single >= 0) {
				ids = new int[4096];
				Arrays.fill(ids, section.single);
			} else {
				ids = new int[4096];
				int[] palette = section.palette;
				int[] columns = section.columns;
				if (columns == null) {
					section.storage.unpack(ids);
					for (int i = 0; i < 4096; i++) {
						ids[i] = palette[ids[i]];
					}
				} else {
					section.storage.unpack(unpacked);
					for (int i = 0; i < 4096; i++) {
						ids[i] = palette[unpacked[(i & 0xF00) | columns[i & 0xFF]]];
					}
				}
			}
			this.ids[index] = ids;
		}
		return ids;
	}

	/** What the section holds: {@link Air#section} where it has no blocks. */
	final Section section(int index) {
		if (index < 0 || index >= this.sections.length) {
			return this.air.section;
		}
		Section section = this.sections[index];
		if (section == null) {
			section = this.read(index);
			this.sections[index] = section;
		}
		return section;
	}
}
