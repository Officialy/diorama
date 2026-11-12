package dev.diorama.bake;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Numeric block, mask, caster and light lookups for one bake job. A source is asked for each chunk
 * once, including missing chunks; prepared section caches and a small direct table keep ray steps
 * allocation-free. Not thread-safe: every job owns its reader and scratch space.
 */
public abstract class VoxelReaderCore {
	public static final int UNKNOWN = -1;
	private static final long[] NOTHING_SOLID = DecodedSections.NOTHING_SOLID;
	private final int airId;
	private final boolean debug;
	final java.util.@Nullable Set<Long> missing;

	/**
	 * The chunk table holds 2^TABLE_BITS chunks each way, by the low bits of their coordinates.
	 * A bake's reach is narrower than that, so its chunks rarely share a slot; when they do, the
	 * slot is filled again from {@link #cache}.
	 */
	private static final int TABLE_BITS = 5;
	private static final int TABLE_MASK = (1 << TABLE_BITS) - 1;

	/** {@link Looks#kind}, for {@link #solid}; null when only {@link #state} is used. */
	private final byte @Nullable [] kinds;
	/** {@link Looks#shape}, so {@link #solid} can tell blocks smaller than a cube; may be null. */
	private final int @Nullable [] shapes;
	private final int minY;
	private final int maxY;
	/**
	 * Every chunk this job has asked the source for, null where it had none. A chunk is asked for
	 * once, so the whole job sees the same blocks even while chunks load and unload.
	 */
	private final Long2ObjectOpenHashMap<@Nullable DecodedSections> cache = new Long2ObjectOpenHashMap<>();
	private final int[] tableX = new int[1 << (2 * TABLE_BITS)];
	private final int[] tableZ = new int[1 << (2 * TABLE_BITS)];
	private final @Nullable DecodedSections[] tableChunk = new DecodedSections[1 << (2 * TABLE_BITS)];
	// The section the last block lookup landed in, in section coordinates, and its ids: null when
	// its chunk is missing.
	private int sectionX = Integer.MIN_VALUE;
	private int sectionY = Integer.MIN_VALUE;
	private int sectionZ = Integer.MIN_VALUE;
	private int @Nullable [] sectionIds;
	// The same for the last solid lookup, with its own section: shadow and sky rays interleave
	// with camera rays. The mask is null when the chunk is missing.
	private int solidX = Integer.MIN_VALUE;
	private int solidY = Integer.MIN_VALUE;
	private int solidZ = Integer.MIN_VALUE;
	private long @Nullable [] solidMask;
	private long @Nullable [] partialMask;
	/** A section's palette indices, unpacked while reading it. */
	private final int[] unpacked = new int[4096];

	protected VoxelReaderCore(int minY, int maxY, int airId, byte @Nullable [] kinds, int @Nullable [] shapes, boolean debug) {
		this.minY = minY;
		this.maxY = maxY;
		this.airId = airId;
		this.kinds = kinds;
		this.shapes = shapes;
		this.debug = debug;
		this.missing = debug ? new java.util.HashSet<>() : null;
		// No chunk has this x, so every slot starts empty.
		Arrays.fill(this.tableX, Integer.MIN_VALUE);
	}

	/** Called only on the first request for a chunk in this job; null is pinned too. */
	protected abstract @Nullable DecodedSections loadChunk(int chunkX, int chunkZ);

	/** Read captured block light for a known chunk, with section index and local coordinates. */
	protected abstract int readBlockLight(DecodedSections chunk, int sectionIndex, int x, int y, int z);

	/** Read captured sky light for a known chunk, with section index and local coordinates. */
	protected abstract int readSkyLight(DecodedSections chunk, int sectionIndex, int x, int y, int z);

	/** Block state id at the position, {@code airId} outside the world, or {@link #UNKNOWN}. */
	public final int state(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return this.airId;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (sx != this.sectionX || sy != this.sectionY || sz != this.sectionZ) {
			this.enterSection(sx, sy, sz);
		}
		int[] ids = this.sectionIds;
		return ids == null ? UNKNOWN : ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
	}

	private void enterSection(int sx, int sy, int sz) {
		DecodedSections chunk = this.chunk(sx, sz);
		if (chunk == null) {
			if (this.debug) {
				this.missing.add(key(sx, sz));
			}
			this.sectionIds = null;
		} else {
			this.sectionIds = chunk.ids(sy - chunk.minSectionY, this.unpacked);
		}
		this.sectionX = sx;
		this.sectionY = sy;
		this.sectionZ = sz;
	}

	/**
	 * 1 if the block is {@link Looks#SOLID}, the kind that stops shadow and sky rays and
	 * casts ambient occlusion, 2 if it is solid but smaller than a cube (rays then need its
	 * shape), 0 if not, or {@link #UNKNOWN}. Reads a bit per block, built once per
	 * section, which keeps far more of the world in the CPU's caches than the ids do.
	 */
	public final int solid(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return 0;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (sx != this.solidX || sy != this.solidY || sz != this.solidZ) {
			this.enterSolidSection(sx, sy, sz);
		}
		long[] mask = this.solidMask;
		if (mask == null) {
			return UNKNOWN;
		}
		int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		if ((mask[i >> 6] >>> i & 1) == 0) {
			return 0;
		}
		return 1 + ((int) (this.partialMask[i >> 6] >>> i) & 1);
	}

	/**
	 * Whether an open cell is a pocket one block tall, with solid blocks right above and below it:
	 * a lone air bubble, a buried lava spring or a crawlspace. Under a roof, rays skipping rock
	 * skip these too, so they don't speckle solid rock with dots; the baker, its lifted rock and
	 * picking on the map must agree on them.
	 */
	final boolean pocket(int x, int y, int z) {
		return this.solid(x, y + 1, z) > 0 && this.solid(x, y - 1, z) > 0;
	}

	/**
	 * Under a roof, the lowest y of the rock lifted off this column: the solid blocks from the cut
	 * at {@code startY} down to the first open space that isn't a {@link #pocket}. Light rays treat
	 * it as open. A pocket right under the cut would depend on the block above it, past the cut,
	 * where edits don't re-bake below, so the open cell just under the cut always ends it.
	 */
	final int lid(int x, int z, int startY, int limitY) {
		int y = startY - 1;
		while (y >= limitY && (this.solid(x, y, z) > 0 || y + 1 < startY && this.pocket(x, y, z))) {
			y--;
		}
		return y + 1;
	}

	/** How much of a ceiling's underside a ray that has left the rock passes through. */
	static final int CEILING_UNDERSIDE = 2;

	/**
	 * Whether this block is in the bottom {@link #CEILING_UNDERSIDE} blocks of the rock lifted off
	 * its column ({@code lid}), with open space right below: the underside of a ceiling, or a block
	 * hanging from it. Walls and pillars have rock below them, so they stay; so does a column that
	 * is rock all the way down to {@code limitY}, where the lid ends without any open space.
	 */
	final boolean ceilingUnderside(int x, int y, int z, int lid, int limitY) {
		return y >= lid && y < lid + CEILING_UNDERSIDE && lid > limitY && this.solid(x, lid - 1, z) == 0;
	}

	private void enterSolidSection(int sx, int sy, int sz) {
		DecodedSections chunk = this.chunk(sx, sz);
		if (chunk == null) {
			if (this.debug) {
				this.missing.add(key(sx, sz));
			}
			this.solidMask = null;
		} else {
			int index = sy - chunk.minSectionY;
			this.solidMask = chunk.solid(index, this.kinds, this.shapes, this.unpacked);
			this.partialMask = chunk.partial(index);
		}
		this.solidX = sx;
		this.solidY = sy;
		this.solidZ = sz;
	}

	/**
	 * A section's {@link #solid} bits, indexed {@code (y << 8) | (z << 4) | x}: null when its chunk
	 * is missing, {@link #NOTHING_SOLID} when nothing in it is solid. Don't write to it.
	 */
	final long @Nullable [] solidSection(int sx, int sy, int sz) {
		DecodedSections chunk = this.chunk(sx, sz);
		return chunk == null ? null : chunk.solid(sy - chunk.minSectionY, this.kinds, this.shapes, this.unpacked);
	}

	/**
	 * Which of a section's {@link #solidSection} bits are blocks smaller than a cube, indexed the
	 * same: null when its chunk is missing, {@link #NOTHING_SOLID} when there are none. Don't write to it.
	 */
	final long @Nullable [] partialSection(int sx, int sy, int sz) {
		DecodedSections chunk = this.chunk(sx, sz);
		if (chunk == null) {
			return null;
		}
		int index = sy - chunk.minSectionY;
		chunk.solid(index, this.kinds, this.shapes, this.unpacked);
		return chunk.partial(index);
	}

	/** The bits of a solid mask where x is 0. */
	private static final long WEST_EDGE = 0x0001_0001_0001_0001L;

	/**
	 * For shadow casters: which of a section's blocks may cast, one bit per
	 * block indexed as {@link #solidSection}. A block without its bit isn't solid, or is a whole
	 * cube with whole cubes above, west and south of it, which gives no caster face. Neighbours in
	 * chunks this job hasn't read yet count as open, so asking reads no chunk the casters wouldn't.
	 * Null where the chunk is missing or the section is outside the world. Don't write to it.
	 */
	public final long @Nullable [] casterSection(int sx, int sy, int sz) {
		DecodedSections chunk = this.chunk(sx, sz);
		if (chunk == null) {
			return null;
		}
		int index = sy - chunk.minSectionY;
		if (index < 0 || index >= chunk.solid.length) {
			return null;
		}
		long[] out = chunk.casters[index];
		if (out == null) {
			long[] solid = chunk.solid(index, this.kinds, this.shapes, this.unpacked);
			long[] partial = chunk.partial(index);
			// The sections above, west and south: solid bits, then smaller blocks; null where none is read.
			int upIndex = index + 1 < chunk.solid.length ? index + 1 : -1;
			long[] upSolid = upIndex < 0 ? null : chunk.solid(upIndex, this.kinds, this.shapes, this.unpacked);
			long[] upPartial = upIndex < 0 ? null : chunk.partial(upIndex);
			DecodedSections west = this.peek(sx - 1, sz);
			int westIndex = west == null ? -1 : sy - west.minSectionY;
			boolean westRead = westIndex >= 0 && westIndex < west.solid.length;
			long[] westSolid = westRead ? west.solid(westIndex, this.kinds, this.shapes, this.unpacked) : null;
			long[] westPartial = westRead ? west.partial(westIndex) : null;
			DecodedSections south = this.peek(sx, sz + 1);
			int southIndex = south == null ? -1 : sy - south.minSectionY;
			boolean southRead = southIndex >= 0 && southIndex < south.solid.length;
			long[] southSolid = southRead ? south.solid(southIndex, this.kinds, this.shapes, this.unpacked) : null;
			long[] southPartial = southRead ? south.partial(southIndex) : null;
			out = new long[64];
			for (int w = 0; w < 64; w++) {
				long cubes = solid[w] & ~partial[w];
				// Four words to a layer of y, four rows of z to a word, x along each row.
				long above = w < 60 ? solid[w + 4] & ~partial[w + 4] : upSolid == null ? 0 : upSolid[w - 60] & ~upPartial[w - 60];
				long westCubes = westSolid == null ? 0 : westSolid[w] & ~westPartial[w];
				long westOf = (cubes << 1) & ~WEST_EDGE | (westCubes >>> 15 & WEST_EDGE);
				long nextRow = (w & 3) < 3 ? solid[w + 1] & ~partial[w + 1] : southSolid == null ? 0 : southSolid[w - 3] & ~southPartial[w - 3];
				long southOf = cubes >>> 16 | nextRow << 48;
				out[w] = solid[w] & ~(cubes & above & westOf & southOf);
			}
			chunk.casters[index] = out;
		}
		return out;
	}

	/** A chunk this job has already read, without asking the source for one it hasn't: null then, or where it had none. */
	private @Nullable DecodedSections peek(int chunkX, int chunkZ) {
		int slot = ((chunkX & TABLE_MASK) << TABLE_BITS) | (chunkZ & TABLE_MASK);
		if (this.tableX[slot] == chunkX && this.tableZ[slot] == chunkZ) {
			return this.tableChunk[slot];
		}
		return this.cache.get(key(chunkX, chunkZ));
	}

	static boolean nothingSolid(long[] mask) {
		return mask == NOTHING_SOLID;
	}

	/**
	 * One above the highest {@link Looks#SOLID} block in these chunks, or
	 * {@code Integer.MIN_VALUE} if they have none. Chunks missing from the source are left out:
	 * every lookup there is {@link #UNKNOWN}. If this job hasn't asked for one of the chunks yet,
	 * returns {@code Integer.MAX_VALUE} instead of asking, so the blocks a job sees don't depend on
	 * this call.
	 */
	final int solidTop(int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
		int top = Integer.MIN_VALUE;
		for (int cx = chunkX0; cx <= chunkX1; cx++) {
			for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
				DecodedSections chunk;
				int slot = ((cx & TABLE_MASK) << TABLE_BITS) | (cz & TABLE_MASK);
				if (this.tableX[slot] == cx && this.tableZ[slot] == cz) {
					chunk = this.tableChunk[slot];
				} else {
					long key = key(cx, cz);
					if (!this.cache.containsKey(key)) {
						return Integer.MAX_VALUE;
					}
					chunk = this.cache.get(key);
				}
				if (chunk != null) {
					top = Math.max(top, chunk.solidTop(this.kinds, this.shapes, this.unpacked));
				}
			}
		}
		return top;
	}

	final int blockLight(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return 0;
		}
		DecodedSections chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			return 0;
		}
		return this.readBlockLight(chunk, (y >> 4) - chunk.minSectionY, x & 15, y & 15, z & 15);
	}

	final int skyLight(int x, int y, int z) {
		if (y < this.minY) {
			return 0;
		}
		if (y >= this.maxY) {
			return 15;
		}
		DecodedSections chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			return 15;
		}
		return this.readSkyLight(chunk, (y >> 4) - chunk.minSectionY, x & 15, y & 15, z & 15);
	}

	protected final @Nullable DecodedSections chunk(int chunkX, int chunkZ) {
		int slot = ((chunkX & TABLE_MASK) << TABLE_BITS) | (chunkZ & TABLE_MASK);
		if (this.tableX[slot] == chunkX && this.tableZ[slot] == chunkZ) {
			return this.tableChunk[slot];
		}
		long key = key(chunkX, chunkZ);
		DecodedSections decoded = this.cache.get(key);
		if (decoded == null && !this.cache.containsKey(key)) {
			decoded = this.loadChunk(chunkX, chunkZ);
			this.cache.put(key, decoded);
		}
		this.tableX[slot] = chunkX;
		this.tableZ[slot] = chunkZ;
		this.tableChunk[slot] = decoded;
		return decoded;
	}

	static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
