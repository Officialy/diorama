package dev.diorama.bake;

import org.jspecify.annotations.Nullable;

/** Numeric, height-keyed column tint blending, independent of biome and world types. */
public final class TintBlend {
	private TintBlend() {
	}

	/** Read-only prepared input for one blend; implementations must not read a live mutable world. */
	public interface Source {
		int surface(int localX, int localZ);

		/** Fill only sampled[channel][cell] and return true; false means an unknown sample, not black. */
		boolean sample(int x, int y, int z, int[][] sampled, int cell);

		/** Fill every output channel from the centre column, used only when no sample was known. */
		void fallback(int x, int y, int z, int[] out);
	}

	/**
	 * RGB results per channel and column; input colours may have alpha, which is ignored.
	 * Uses the captured non-negative radius and fixed channel count for this blend. Cache arrays
	 * belong to this call: the source may write only its requested sample cell or fallback output.
	 */
	public static int[][] blend(int chunkX, int chunkZ, int radius, int channels, Source source) {
		// Where the game had no chunk, the lookup gives null, and the blend leaves that block out:
		// counting the biome the game assumes there, Plains, tinted the edge of the loaded area (#290).
		int[][] colors = new int[channels][256];
		int[] fallback = new int[channels];
		int[] red = new int[channels];
		int[] green = new int[channels];
		int[] blue = new int[channels];
		// Neighbouring columns share most of the blocks they blend, at the same height unless the
		// ground steps, so each block's colours are worked out once per height.
		int size = 16 + radius * 2;
		int[] heights = new int[size * size];
		java.util.Arrays.fill(heights, Integer.MIN_VALUE);
		int[][] sampled = new int[channels][size * size];
		boolean[] known = new boolean[size * size];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int bx = (chunkX << 4) + x;
				int bz = (chunkZ << 4) + z;
				int y = source.surface(x, z);
				java.util.Arrays.fill(red, 0);
				java.util.Arrays.fill(green, 0);
				java.util.Arrays.fill(blue, 0);
				int count = 0;
				for (int dz = -radius; dz <= radius; dz++) {
					for (int dx = -radius; dx <= radius; dx++) {
						int cell = (z + dz + radius) * size + x + dx + radius;
						if (heights[cell] != y) {
							heights[cell] = y;
							known[cell] = source.sample(bx + dx, y, bz + dz, sampled, cell);
						}
						if (known[cell]) {
							count++;
							for (int r = 0; r < channels; r++) {
								int color = sampled[r][cell];
								red[r] += (color >> 16 & 255);
								green[r] += (color >> 8 & 255);
								blue[r] += (color & 255);
							}
						}
					}
				}
				if (count == 0) {
					// Every lookup landed in a chunk the game didn't have, which only happens next to
					// one with the blend off or small: the column's own biome, without the fuzz.
					source.fallback(bx, y, bz, fallback);
					count = 1;
					for (int r = 0; r < channels; r++) {
						int color = fallback[r];
						red[r] = color >> 16 & 255;
						green[r] = color >> 8 & 255;
						blue[r] = color & 255;
					}
				}
				int i = (z << 4) | x;
				for (int r = 0; r < channels; r++) {
					colors[r][i] = (red[r] / count << 16) | (green[r] / count << 8) | blue[r] / count;
				}
			}
		}
		return colors;
	}

	/** Copy colours into a view using its prepared column map; null is the identity map. */
	public static int[] turn(int[] colors, int @Nullable [] columns) {
		int[] out = new int[256];
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int i = (z << 4) | x;
				out[i] = colors[columns == null ? i : columns[i]];
			}
		}
		return out;
	}
}
