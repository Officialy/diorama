package dev.diorama.bake;

import dev.diorama.MapStyle;

/** Shared map-sun direction, independent of craft and terrain-baker inputs. */
final class SunDirection {
	static final double AZIMUTH_X = -0.878;
	static final double AZIMUTH_Z = 0.479;

	private SunDirection() {
	}

	/** Toward the sun, as a unit vector. */
	static double[] sun(MapStyle style) {
		double elevation = Math.toRadians(style.sunElevation());
		return new double[] {Math.cos(elevation) * AZIMUTH_X, Math.sin(elevation), Math.cos(elevation) * AZIMUTH_Z};
	}
}
