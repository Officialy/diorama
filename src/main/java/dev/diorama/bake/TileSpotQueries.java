package dev.diorama.bake;

import dev.diorama.Projection;
import org.jspecify.annotations.Nullable;

/** Numeric saved-spot queries shared by terrain tracing and modern map picking. */
public final class TileSpotQueries {
	static final int COLUMNS = Projection.TILE_UNITS;
	static final int ROWS = Projection.TILE_PIXELS;
	static final int SPOT = 2 << 24;

	private TileSpotQueries() {
	}

	/** The middle of the pixel row map row {@code v} falls in. */
	public static double rowMiddle(double v) {
		return (Math.floor(v * Projection.PIXELS_PER_UNIT) + 0.5) / Projection.PIXELS_PER_UNIT;
	}

	/**
	 * Whether the saved ray through block column {@code x} and map row {@code v} of a level-0
	 * surface tile passed through height {@code y} on its way down: it stood on something lower, so
	 * whatever it crossed up there was open when the tile was saved.
	 *
	 * <p>A ray that reaches a chunk the map has no data for can take the cell as open on that word.
	 * Rays start at the top of the tallest chunk around the tile, so where a hill or a tower stands
	 * nearby they cross chunks well south of what they land on; on a server those are often chunks
	 * that are never sent while the player is close. Without this, the fresh pixel comes out less
	 * known than the saved one, the saved one stays, and nothing built there since ever shows.
	 * Below the saved spot the cell stays unknown: the saved ray may have landed in that chunk. A ray
	 * goes on through glass and plants, so glass or a tall plant in a chunk taken as open draws as
	 * air until the chunk is back; only its tint is lost.
	 */
	private static boolean passedWhenSaved(TileKey key, int @Nullable [] spots, int x, int y, double v) {
		if (spots == null) {
			return false;
		}
		long column = x - (long) Math.floor(key.minU());
		long row = (long) Math.floor((v - key.minV()) * Projection.PIXELS_PER_UNIT);
		if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
			return false;
		}
		int value = spots[(int) row * COLUMNS + (int) column];
		return (value & ~0xFFFFFF) == SPOT && y > (short) value;
	}

	/**
	 * Whether every saved ray of the tile's pixel rows crossing block {@code (x, y, z)} passed
	 * through it, so the block can be taken as open; false if none of them is in the tile. A spot
	 * comes from the middle of its row, while a pixel's samples spread across it, so one row's word
	 * isn't enough: at a tower's edge the middle can pass while a sample hits. Only rows whose middle
	 * crosses the block speak for it, so at least one has to, and every row reaching it has to agree.
	 * The tracer, the GPU mesher and the pick all ask this, so they agree.
	 */
	public static boolean passedWhenSaved(TileKey key, int @Nullable [] spots, int x, int y, int z) {
		if (spots == null) {
			return false;
		}
		double sin = Projection.sin();
		double cos = Projection.cos();
		double first = Math.max(key.minV(), z * sin - (y + 1) * cos);
		double last = Math.min(key.minV() + Projection.TILE_UNITS, (z + 1) * sin - y * cos);
		if (first >= last) {
			return false;
		}
		double step = 1.0 / Projection.PIXELS_PER_UNIT;
		boolean crossed = false;
		for (double v = rowMiddle(first); v - step / 2 < last; v += step) {
			if (!passedWhenSaved(key, spots, x, y, v)) {
				return false;
			}
			crossed |= v >= first && v < last;
		}
		return crossed;
	}
}
