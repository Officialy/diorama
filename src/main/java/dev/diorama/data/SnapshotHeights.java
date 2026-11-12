package dev.diorama.data;

import org.jspecify.annotations.Nullable;

/** Snapshot height bounds, shared without knowing how a platform stores section contents. */
public final class SnapshotHeights {
	private SnapshotHeights() {
	}

	/** One above a column top below the ceiling, or Integer.MIN_VALUE if none is known. */
	public static int topBelow(int maxY, int[] surface, int ceiling) {
		if (maxY < ceiling) {
			return maxY == Integer.MIN_VALUE ? Integer.MIN_VALUE : maxY + 1;
		}
		int top = Integer.MIN_VALUE;
		for (int y : surface) {
			if (y < ceiling) {
				top = Math.max(top, y + 1);
			}
		}
		return top;
	}

	/** Bottom of the first present section from y upwards; y itself when none is present. */
	public static int firstSectionFrom(int minSectionY, @Nullable Object[] states, @Nullable Object @Nullable [] uniform, int y) {
		for (int i = Math.max(0, (y >> 4) - minSectionY); i < states.length; i++) {
			if (states[i] != null || uniform != null && uniform[i] != null) {
				return (minSectionY + i) << 4;
			}
		}
		return y;
	}

	/** Top of the last present section below the one holding y; Integer.MIN_VALUE if none is. */
	public static int lastSectionTopBelow(int minSectionY, @Nullable Object[] states, @Nullable Object @Nullable [] uniform, int y) {
		for (int i = Math.min(states.length, (y >> 4) - minSectionY) - 1; i >= 0; i--) {
			if (states[i] != null || uniform != null && uniform[i] != null) {
				return (minSectionY + i + 1) << 4;
			}
		}
		return Integer.MIN_VALUE;
	}
}
