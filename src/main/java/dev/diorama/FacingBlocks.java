package dev.diorama;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft blocks in the turned space of a {@link Facing}. */
public final class FacingBlocks {
	private FacingBlocks() {
	}

	/** The world's block at a block in turned space. */
	public static BlockPos worldPos(Facing facing, BlockPos turned) {
		return new BlockPos(facing.worldBlockX(turned.getX(), turned.getZ()), turned.getY(), facing.worldBlockZ(turned.getX(), turned.getZ()));
	}

	/** A block as turned space has it. */
	public static BlockState turned(Facing facing, BlockState state) {
		if (facing == Facing.NORTH) {
			return state;
		}
		try {
			return state.rotate(switch (facing) {
				case NORTH -> Rotation.NONE;
				case EAST -> Rotation.COUNTERCLOCKWISE_90;
				case SOUTH -> Rotation.CLOCKWISE_180;
				case WEST -> Rotation.CLOCKWISE_90;
			});
		} catch (RuntimeException e) {
			// A mod's block that can't be turned is drawn as it stands.
			return state;
		}
	}
}
