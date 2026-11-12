package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Blends a snapshot's biome tints on a bake thread, the way the game blends them for its own
 * blocks: each column's tint is the average of the biome colors over the blend radius around
 * its top block, with biomes looked up through the same fuzzed zoom. Blending on the client
 * thread took most of its time while exploring, so snapshots only hold on to the biomes, their
 * own and their neighbours', as the game had them when the snapshot was taken.
 */
public final class BiomeTints {
	private static final ColorResolver[] RESOLVERS = {
		BiomeColors.GRASS_COLOR_RESOLVER,
		BiomeColors.FOLIAGE_COLOR_RESOLVER,
		//? if >=1.21.5 {
		BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
		//?} else {
		/*// Dry foliage (leaf litter) came in 1.21.5; nothing reads this slot before.
		BiomeColors.FOLIAGE_COLOR_RESOLVER,
		*///?}
		BiomeColors.WATER_COLOR_RESOLVER,
	};
	private static final int WATER = 3;

	private BiomeTints() {
	}

	/**
	 * The biome colour of water: the one the water's own tint code asks for, which a mod can make
	 * its own (Natural Waters). {@link BlockLooks#build} finds it out.
	 */
	public static ColorResolver water() {
		return RESOLVERS[WATER];
	}

	static void setWater(ColorResolver resolver) {
		RESOLVERS[WATER] = resolver;
	}

	/** The snapshot's tints, blended now if no bake has needed them yet. Any thread. */
	public static ChunkSnapshot.Tints of(ChunkSnapshot snapshot) {
		ChunkSnapshot.Tints tints = snapshot.tints();
		if (tints == null) {
			// Two bakes may both blend; they read the same biomes, so either result will do.
			// A turned view blends its chunk, and turns what that gives.
			ChunkSnapshot chunk = snapshot.chunk();
			tints = blend(chunk);
			chunk.setTints(tints);
			if (chunk != snapshot) {
				tints = snapshot.tints();
			}
		}
		return tints;
	}

	/** Blends the snapshot's tints. Any thread. */
	public static ChunkSnapshot.Tints blend(ChunkSnapshot snapshot) {
		int[][] colors = TintBlend.blend(snapshot.chunkX, snapshot.chunkZ, snapshot.blendRadius, RESOLVERS.length, new Samples(snapshot));
		return new ChunkSnapshot.Tints(colors[0], colors[1], colors[2], colors[WATER]);
	}

	/** One sampler per blend; all reads come from the already captured biome neighbourhood. */
	private static final class Samples implements TintBlend.Source {
		private final ChunkSnapshot snapshot;
		private final BiomeManager biomes;
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		Samples(ChunkSnapshot snapshot) {
			this.snapshot = snapshot;
			this.biomes = new BiomeManager(snapshot::knownNoiseBiome, snapshot.biomeZoomSeed);
		}

		@Override
		public int surface(int x, int z) {
			return this.snapshot.surface(x, z);
		}

		@Override
		public boolean sample(int x, int y, int z, int[][] sampled, int cell) {
			Holder<Biome> biome = this.biomes.getBiome(this.pos.set(x, y, z));
			for (int r = 0; biome != null && r < RESOLVERS.length; r++) {
				sampled[r][cell] = RESOLVERS[r].getColor(biome.value(), x, z);
			}
			return biome != null;
		}

		@Override
		public void fallback(int x, int y, int z, int[] out) {
			Biome own = this.snapshot.noiseBiome(x >> 2, y >> 2, z >> 2).value();
			for (int r = 0; r < RESOLVERS.length; r++) {
				out[r] = RESOLVERS[r].getColor(own, x, z);
			}
		}
	}
}
