package dev.diorama.bake;

import net.minecraft.world.level.levelgen.WorldgenRandom;

/** Modern platform binding for Minecraft's seeded slime-chunk rule. */
final class SlimeChunks {
	private SlimeChunks() {
	}

	static boolean slimeChunk(long seed, int chunkX, int chunkZ) {
		return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, 987234911L).nextInt(10) == 0;
	}
}
