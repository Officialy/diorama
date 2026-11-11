package dev.diorama;

/**
 * Which way the map looks. The bakers, picks and lights only know a camera looking north; another
 * facing turns the world under them, block states included, so they see its other side, with the
 * sun in its place on the picture. Turned space is what they work in: the map's u and v are its x
 * and tilted z.
 *
 * <p>Whole blocks turn about a corner, so a chunk lands exactly on a chunk: a block at -x - 1 fills
 * the place a point at -x reaches to.
 */
public enum Facing {
	NORTH(0),
	EAST(1),
	SOUTH(2),
	WEST(3);

	/** Quarter turns from north. */
	public final int turns;

	Facing(int turns) {
		this.turns = turns;
	}

	/** The word for this facing in folder and file names. */
	public String id() {
		return this.name().toLowerCase(java.util.Locale.ROOT);
	}

	public static Facing of(String id) {
		for (Facing facing : values()) {
			if (facing.id().equals(id)) {
				return facing;
			}
		}
		return NORTH;
	}

	public Facing next(int steps) {
		return values()[Math.floorMod(this.turns + steps, 4)];
	}

	/** The block in turned space, or a chunk: they turn alike. */
	public int blockX(int x, int z) {
		return switch (this) {
			case EAST -> z;
			case SOUTH -> -x - 1;
			case WEST -> -z - 1;
			default -> x;
		};
	}

	public int blockZ(int x, int z) {
		return switch (this) {
			case EAST -> -x - 1;
			case SOUTH -> -z - 1;
			case WEST -> x;
			default -> z;
		};
	}

	/** The world's block or chunk at a place in turned space. */
	public int worldBlockX(int x, int z) {
		return switch (this) {
			case EAST -> -z - 1;
			case SOUTH -> -x - 1;
			case WEST -> z;
			default -> x;
		};
	}

	public int worldBlockZ(int x, int z) {
		return switch (this) {
			case EAST -> x;
			case SOUTH -> -z - 1;
			case WEST -> -x - 1;
			default -> z;
		};
	}

	/** A point in turned space. */
	public double x(double x, double z) {
		return switch (this) {
			case EAST -> z;
			case SOUTH -> -x;
			case WEST -> -z;
			default -> x;
		};
	}

	public double z(double x, double z) {
		return switch (this) {
			case EAST -> -x;
			case SOUTH -> -z;
			case WEST -> x;
			default -> z;
		};
	}

	/** The world's point at a point in turned space. */
	public double worldX(double x, double z) {
		return switch (this) {
			case EAST -> -z;
			case SOUTH -> -x;
			case WEST -> z;
			default -> x;
		};
	}

	public double worldZ(double x, double z) {
		return switch (this) {
			case EAST -> x;
			case SOUTH -> -z;
			case WEST -> -x;
			default -> z;
		};
	}
}
