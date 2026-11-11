package dev.diorama.bake;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Primitive, lazily published looks read by tracers and meshers. Platform-specific subclasses
 * supply looks under their own making lock; published looks never change.
 */
public abstract class Looks {
	public static final byte AIR = 0;
	public static final byte SOLID = 1;
	public static final byte WATER = 2;
	public static final byte LAVA = 3;
	/** See-through blocks such as glass: rays pass and pick up a tint. */
	public static final byte GLASS = 4;
	/** Plants without collision: rays pass and pick up partial color. */
	public static final byte PLANT = 5;

	public static final byte TINT_NONE = 0;
	public static final byte TINT_GRASS = 1;
	public static final byte TINT_FOLIAGE = 2;
	public static final byte TINT_DRY_FOLIAGE = 3;
	public static final byte TINT_WATER = 4;

	/** {@link #kind} of a state whose look nobody has asked for yet. */
	protected static final byte NOT_YET = -1;
	/** Reads and writes {@link #kind}: a look's other fields are written before its kind is released. */
	protected static final VarHandle KINDS = MethodHandles.arrayElementVarHandle(byte[].class);

	/** Kind per state; {@link #NOT_YET} until {@link #ensure} works the look out. */
	public final byte[] kind;
	public final byte[] tint;
	public final byte[] emission;
	/** Untinted part of the top color, as 0xRRGGBB. */
	public final int[] top;
	/** Part of the top color that gets multiplied by the biome tint. */
	public final int[] topTinted;
	public final float[] topTintWeight;
	public final int[] side;
	public final int[] sideTinted;
	public final float[] sideTintWeight;
	/** Fixed tint for blocks whose tint doesn't depend on the biome, or -1. */
	public final int[] constantTint;
	/** Whether a monster can spawn standing on this block (not on leaves or glass, say). */
	public final boolean[] spawnOn;
	/**
	 * Whether a monster's body fits in this block's space: Minecraft's empty-spawn-block rule, and
	 * no collision at all, since a slab at head height blocks a zombie too.
	 */
	public final boolean[] spawnIn;
	/**
	 * Index into {@link #shapes} of a solid block's outline, or 0 for a full cube: everything else,
	 * and any block whose outline is empty, rays treat as a whole cube.
	 */
	public final int[] shape;
	/**
	 * Outlines of solid blocks smaller than a cube, such as slabs, stairs, fences and carpets: each
	 * box as six floats (min x, y, z, then max x, y, z) in block units. Entry 0 is unused. Grows
	 * as looks are worked out, published before the look that uses it; entries past the last
	 * outline are null.
	 */
	public volatile float[][] shapes = {new float[0]};
	/** {@link #faces} so far, by shape; made on first use. */
	private volatile @Nullable ShapeFaces @Nullable [] faces;
	/**
	 * For a waterlogged block smaller than a cube, the state id of the fluid around it: rays that
	 * pass beside the block, or reach it from above, go through that fluid. -1 for every other block.
	 */
	public final int[] wetShape;

	/**
	 * How many filled-copycat looks fit past the registry. Each pairing of a copycat state with a
	 * material takes one; past this many, more copycats draw as they do empty.
	 */
	protected static final int COPYCAT_LOOKS = 1 << 14;
	/** The block state registry's size: ids from here on are filled copycats. */
	protected final int registrySize;
	/** Filled-copycat look ids, by copycat state id in the high half and material state id in the low half. */
	private final Map<Long, Integer> copycatIds = new ConcurrentHashMap<>();
	private final AtomicInteger copycatCount = new AtomicInteger();
	private volatile boolean warnedCopycats;

	// Outlines found so far, used only while a look is worked out (one at a time, see make).
	private final Map<String, Integer> shapeByBoxes = new HashMap<>();
	private final List<float[]> shapeList = new ArrayList<>(List.of(new float[0]));

	protected Looks(int registrySize, int size) {
		this.registrySize = registrySize;
		this.kind = new byte[size];
		this.tint = new byte[size];
		this.emission = new byte[size];
		this.top = new int[size];
		this.topTinted = new int[size];
		this.topTintWeight = new float[size];
		this.side = new int[size];
		this.sideTinted = new int[size];
		this.sideTintWeight = new float[size];
		this.constantTint = new int[size];
		this.spawnOn = new boolean[size];
		this.spawnIn = new boolean[size];
		this.shape = new int[size];
		this.wetShape = new int[size];
		java.util.Arrays.fill(this.wetShape, -1);
		java.util.Arrays.fill(this.kind, 0, registrySize, NOT_YET);
	}

	/** Works out a look under the subclass lock and publishes it with {@link #publish}. */
	protected abstract void make(int id);

	/** Publishes the completed look after all its fields have been written. */
	protected final void publish(int id, byte kind) {
		KINDS.setRelease(this.kind, id, kind);
	}

	/** Writes both faces' colours into the state's look. */
	protected void setColours(int id, FaceColor topFace, FaceColor sideFace) {
		this.top[id] = topFace.untinted();
		this.topTinted[id] = topFace.tinted();
		this.topTintWeight[id] = topFace.tintWeight();
		this.side[id] = sideFace.untinted();
		this.sideTinted[id] = sideFace.tinted();
		this.sideTintWeight[id] = sideFace.tintWeight();
	}

	/**
	 * What the GPU baker draws of {@link #shapes}{@code [shape]}. The mesher asks for every smaller
	 * block it meets, so this keeps them by index rather than going through ShapeFaces' shared,
	 * locked table each time. Any thread.
	 */
	ShapeFaces faces(int shape) {
		ShapeFaces[] faces = this.faces;
		if (faces == null || shape >= faces.length) {
			// More outlines since: a longer copy. Two threads may both grow it, and one's entries are
			// lost; they're made again when next asked.
			faces = java.util.Arrays.copyOf(faces == null ? new ShapeFaces[0] : faces, this.shapes.length);
			this.faces = faces;
		}
		ShapeFaces made = faces[shape];
		if (made == null) {
			// Two threads may both look it up; they get the same one.
			made = ShapeFaces.of(this.shapes[shape]);
			faces[shape] = made;
		}
		return made;
	}

	/**
	 * Works the state's look out if nothing has asked for it yet, and returns the id. Any thread:
	 * the first to ask does the work, others asking meanwhile wait for it. Every id whose look is
	 * read here comes through this first, or through a method that calls it.
	 */
	public int ensure(int id) {
		if (id >= 0 && id < this.registrySize && (byte) KINDS.getAcquire(this.kind, id) == NOT_YET) {
			this.make(id);
		}
		return id;
	}

	/** Stops a bake while platform state ids are being numbered again. */
	public static final class StatesRenumbered extends RuntimeException {
		static final StatesRenumbered INSTANCE = new StatesRenumbered();

		private StatesRenumbered() {
			super("Block states are being numbered again", null, false, false);
		}
	}

	/**
	 * True when two states draw the same on the map. Leaves changing their distance property,
	 * for example, is a block update nobody can see.
	 */
	public boolean sameLook(int a, int b) {
		this.ensure(a);
		this.ensure(b);
		return this.kind[a] == this.kind[b]
			&& this.top[a] == this.top[b]
			&& this.topTinted[a] == this.topTinted[b]
			&& this.side[a] == this.side[b]
			&& this.sideTinted[a] == this.sideTinted[b]
			&& this.topTintWeight[a] == this.topTintWeight[b]
			&& this.sideTintWeight[a] == this.sideTintWeight[b]
			&& this.tint[a] == this.tint[b]
			&& this.constantTint[a] == this.constantTint[b]
			&& this.emission[a] == this.emission[b]
			// Spawn spots show on the map too: a slab becoming a double slab changes them.
			&& this.spawnOn[a] == this.spawnOn[b]
			&& this.spawnIn[a] == this.spawnIn[b]
			// Same index, same boxes: outlines are stored once each.
			&& this.shape[a] == this.shape[b]
			// Filling or draining a waterlogged slab changes nothing else.
			&& this.wetShape[a] == this.wetShape[b];
	}

	/**
	 * The look of a copycat filled with a material, both given as state ids: the copycat's outline,
	 * spawn rules and water, with the material's colours, tint and light. Rays pass through it if
	 * they pass through the material, as through a copycat filled with glass, and pass through a
	 * copycat without collision (a button, a pressure plate) as they do empty. Any thread: a bake
	 * calls this when it reads a snapshot's copycats, and the look is filled in before its id is
	 * handed out, so a bake only ever reads looks that are done. Returns the copycat's own id when
	 * there's nothing to copy, or no room left.
	 */
	public int copycat(int copycat, int material) {
		if (copycat < 0 || copycat >= this.registrySize || material < 0 || material >= this.registrySize || this.kind.length == this.registrySize) {
			return this.ensure(copycat);
		}
		this.ensure(copycat);
		this.ensure(material);
		byte frame = this.kind[copycat];
		// A material left unworked (states being numbered again) counts as none.
		if (frame != SOLID && frame != GLASS && frame != PLANT || this.kind[material] == AIR || this.kind[material] == NOT_YET) {
			return copycat;
		}
		Long key = (long) copycat << 32 | material;
		Integer id = this.copycatIds.get(key);
		if (id != null) {
			return id;
		}
		if (this.copycatCount.get() >= this.kind.length - this.registrySize) {
			// Full: don't grow the table with pairs that would all draw empty anyway.
			this.warnFull();
			return copycat;
		}
		return this.copycatIds.computeIfAbsent(key, unused -> this.addCopycat(copycat, material));
	}

	private void warnFull() {
		if (!this.warnedCopycats) {
			this.warnedCopycats = true;
			this.warnCopycatsFull();
		}
	}

	private int addCopycat(int copycat, int material) {
		int id = this.registrySize + this.copycatCount.getAndIncrement();
		if (id >= this.kind.length) {
			// Bake threads that passed the check above together.
			this.warnFull();
			return copycat;
		}
		this.kind[id] = this.kind[copycat] == PLANT ? PLANT : this.kind[material] == GLASS ? GLASS : SOLID;
		this.shape[id] = this.shape[copycat];
		this.spawnOn[id] = this.spawnOn[copycat];
		this.spawnIn[id] = this.spawnIn[copycat];
		this.wetShape[id] = this.wetShape[copycat];
		this.top[id] = this.top[material];
		this.topTinted[id] = this.topTinted[material];
		this.topTintWeight[id] = this.topTintWeight[material];
		this.side[id] = this.side[material];
		this.sideTinted[id] = this.sideTinted[material];
		this.sideTintWeight[id] = this.sideTintWeight[material];
		this.tint[id] = this.tint[material];
		this.constantTint[id] = this.constantTint[material];
		this.emission[id] = this.emission[material];
		return id;
	}

	/** Platform warning when filled-copycat capacity is first exhausted. */
	protected void warnCopycatsFull() {}

	/**
	 * Interns owned, normalized boxes in their exact order, under the subclass making lock.
	 * The caller handles empty and full-cube shapes and must not mutate the array afterwards.
	 */
	protected final int internShape(float[] ownedNormalizedBoxes) {
		List<float[]> shapes = this.shapeList;
		return this.shapeByBoxes.computeIfAbsent(java.util.Arrays.toString(ownedNormalizedBoxes), key -> {
			shapes.add(ownedNormalizedBoxes);
			// Room for more by doubling; the entry is published before the look that uses it.
			float[][] published = this.shapes;
			if (published.length < shapes.size()) {
				published = java.util.Arrays.copyOf(published, Math.max(16, published.length * 2));
			}
			published[shapes.size() - 1] = ownedNormalizedBoxes;
			this.shapes = published;
			return shapes.size() - 1;
		});
	}
}
