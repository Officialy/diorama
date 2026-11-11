package dev.diorama.bake;

import dev.diorama.Diorama;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Modern Minecraft state classification, registry lookup and outline extraction for looks. */
public abstract class StateLooks extends Looks {
	private final Map<VoxelShape, Integer> shapeByVoxels = new IdentityHashMap<>();
	private boolean warnedShape;

	protected StateLooks(int registrySize, int size) {
		super(registrySize, size);
	}

	/**
	 * Fills in the state's colours and tint for a look whose kind the game's rules make
	 * {@code kind}, and returns its kind, which the colours can change ({@link #GLASS} for a
	 * see-through texture, {@link #AIR} for a block with nothing to show). Under the lock of
	 * {@link #make}.
	 */
	protected abstract byte colours(int id, BlockState state, byte kind);

	/**
	 * What {@link #colours} gives a block with no texture to go by: its colour on Minecraft's own
	 * maps, seen through where those maps see through it ({@link #AIR}). Returns its kind.
	 */
	protected byte flatColours(int id, BlockState state, byte kind) {
		int mapColor = mapColor(state);
		if (mapColor == 0) {
			return AIR;
		}
		FaceColor face = new FaceColor();
		face.add(new float[] {(mapColor >> 16 & 0xFF) / 255.0F, (mapColor >> 8 & 0xFF) / 255.0F, (mapColor & 0xFF) / 255.0F, 1.0F}, -1);
		this.setColours(id, face, face);
		return kind;
	}

	/** The block's colour on Minecraft's own maps as 0xRRGGBB, or 0 where those maps see through it. */
	protected static int mapColor(BlockState state) {
		try {
			return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
		} catch (RuntimeException | Error e) {
			return 0;
		}
	}

	/**
	 * Fills in the state's look but its kind, which it returns: the game's rules here, the
	 * colours from {@link #colours}. Under the lock of {@link #make}.
	 */
	protected final byte work(int id, BlockState state) {
		this.emission[id] = (byte) state.getLightEmission();
		this.constantTint[id] = -1;
		// Minecraft's own spawn rules, asked of the block alone: none of them look around it.
		try {
			this.spawnOn[id] = state.isValidSpawn(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, EntityTypes.ZOMBIE);
			this.spawnIn[id] = NaturalSpawner.isValidEmptySpawnBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state, state.getFluidState(), EntityTypes.ZOMBIE)
				&& state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		} catch (RuntimeException | Error e) {
			this.spawnOn[id] = false;
			this.spawnIn[id] = false;
		}
		// A mod's block can give a null shape or throw when asked, and under ModernFix, which builds
		// each state's cache on first use, so can anything that reads the cache. One that fails
		// counts as a full block and keeps its model's colours.
		byte kind = SOLID;
		try {
			kind = classify(state);
			if (kind == SOLID) {
				this.shape[id] = this.shapeIndex(state);
			}
		} catch (RuntimeException | Error e) {
			if (!this.warnedShape) {
				this.warnedShape = true;
				Diorama.LOGGER.warn("The shape of {} failed when asked for ({}); such blocks draw as full blocks", state, e.toString());
			}
		}
		if (kind == AIR) {
			return AIR;
		}
		kind = this.colours(id, state, kind);
		if (kind == AIR) {
			this.shape[id] = 0;
			return AIR;
		}
		if (this.shape[id] != 0 && !state.getFluidState().isEmpty()) {
			int fluid = Block.BLOCK_STATE_REGISTRY.getId(state.getFluidState().createLegacyBlock());
			if (fluid >= 0 && fluid < this.registrySize && fluid != id && this.kind[this.ensure(fluid)] == WATER) {
				this.wetShape[id] = fluid;
			}
		}
		return kind;
	}

	/**
	 * The state's id in the tables here. A state the game hasn't numbered, which some mods make
	 * on the fly (Epic Fight's cracked ground, #180), takes its block's default state's id, or
	 * air's. Only numbers it: {@link #lookId} also works its look out.
	 */
	public static int id(BlockState state) {
		int id = Block.BLOCK_STATE_REGISTRY.getId(state);
		if (id < 0) {
			id = Block.BLOCK_STATE_REGISTRY.getId(state.getBlock().defaultBlockState());
		}
		if (id < 0) {
			id = Block.BLOCK_STATE_REGISTRY.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		}
		return Math.max(id, 0);
	}

	/**
	 * {@link #id} for a bake. While NeoForge or Forge number every state again, as they do when a
	 * world is left, states have no id for a moment and the table is short: the bake stops
	 * ({@link StatesRenumbered}) rather than read those blocks as something else, since the map
	 * still saves its last bakes as it closes.
	 */
	protected static int bakeId(BlockState state, @Nullable Looks looks) {
		int id = Block.BLOCK_STATE_REGISTRY.getId(state);
		if (id >= 0) {
			return id;
		}
		if (looks != null && Block.BLOCK_STATE_REGISTRY.size() != looks.registrySize) {
			throw StatesRenumbered.INSTANCE;
		}
		return id(state);
	}

	/** {@link #id}, with the state's look worked out. */
	public int lookId(BlockState state) {
		return this.ensure(id(state));
	}

	/**
	 * {@link #bakeId}, with the state's look worked out. A look left unworked because states are
	 * being numbered again stops the bake ({@link StatesRenumbered}), as {@link #bakeId} does.
	 */
	public int bakeLookId(BlockState state) {
		return bakeLookId(this, state);
	}

	/** State lookup for a bake using any primitive look table. */
	public static int bakeLookId(Looks looks, BlockState state) {
		int id = looks.ensure(bakeId(state, looks));
		if (id < looks.registrySize && (byte) KINDS.getAcquire(looks.kind, id) == NOT_YET) {
			throw StatesRenumbered.INSTANCE;
		}
		return id;
	}

	@Override
	protected void warnCopycatsFull() {
		Diorama.LOGGER.warn("More than {} kinds of filled copycat; the rest draw as they do empty", COPYCAT_LOOKS);
	}

	/** A shape with more boxes than this counts as its bounding box. */
	private static final int MAX_BOXES = 16;

	/** Finds or adds the state's outline in {@link #shapes}; 0 for a full cube. Under the lock of {@link #make}. */
	private int shapeIndex(BlockState state) {
		Map<VoxelShape, Integer> byVoxels = this.shapeByVoxels;
		VoxelShape outline;
		try {
			outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (RuntimeException | Error e) {
			return 0;
		}
		Integer known = byVoxels.get(outline);
		if (known != null) {
			return known;
		}
		int index = 0;
		if (!outline.isEmpty() && outline != Shapes.block()) {
			List<AABB> boxes = outline.toAabbs();
			if (boxes.size() > MAX_BOXES) {
				boxes = List.of(outline.bounds());
			}
			float[] flat = new float[boxes.size() * 6];
			int n = 0;
			boolean full = false;
			for (AABB box : boxes) {
				// Some mods' outlines reach into the next block; only the part in this one counts,
				// and a box wholly outside it is dropped.
				float[] b = {clamp01(box.minX), clamp01(box.minY), clamp01(box.minZ), clamp01(box.maxX), clamp01(box.maxY), clamp01(box.maxZ)};
				if (b[0] >= b[3] || b[1] >= b[4] || b[2] >= b[5]) {
					continue;
				}
				System.arraycopy(b, 0, flat, n, 6);
				n += 6;
				full |= b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 1 && b[4] == 1 && b[5] == 1;
			}
			float[] kept = java.util.Arrays.copyOf(flat, n);
			if (!full && n > 0) {
				index = this.internShape(kept);
			}
		}
		byVoxels.put(outline, index);
		return index;
	}

	private static float clamp01(double v) {
		return (float) Math.max(0, Math.min(1, v));
	}

	private static byte classify(BlockState state) {
		if (state.isAir() || state.getRenderShape() == RenderShape.INVISIBLE && state.getFluidState().isEmpty()) {
			return AIR;
		}
		FluidState fluid = state.getFluidState();
		boolean noCollision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
		if (!fluid.isEmpty() && noCollision) {
			return fluid.is(FluidTags.LAVA) ? LAVA : WATER;
		}
		if (noCollision) {
			return state.getBlock() instanceof SnowLayerBlock ? SOLID : PLANT;
		}
		return SOLID;
	}
}
