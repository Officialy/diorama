package dev.diorama.bake;

/** Alpha-weighted stored sRGB colour and visible-pixel coverage, independent of image types. */
public final class SpriteAverage {
	private double r;
	private double g;
	private double b;
	private double weight;
	private int visible;

	/** Add one pixel in ARGB order; alpha below 26 contributes neither colour nor coverage. */
	public void add(int argb) {
		int a = argb >>> 24;
		if (a < 26) {
			return;
		}
		double wa = a / 255.0;
		// Average the stored (sRGB) values, as Xaero's World Map and JourneyMap do. In linear
		// light the bright pixels outweigh the dark ones: a casing that is a dark panel in a
		// brass frame came out the frame's colour, and an enchanting table's red top grey.
		r += ((argb >> 16) & 0xFF) * wa;
		g += ((argb >> 8) & 0xFF) * wa;
		b += (argb & 0xFF) * wa;
		weight += wa;
		visible++;
	}

	/**
	 * Return a fresh {red, green, blue, coverage} array, with colour and coverage in [0, 1].
	 * pixelCount is the positive total sampled area, including invisible pixels; the caller
	 * must add each pixel in that area once. This does not reset or consume the accumulator.
	 */
	public float[] finish(int pixelCount) {
		if (pixelCount <= 0) {
			throw new IllegalArgumentException("pixelCount must be positive");
		}
		if (weight == 0) {
			return new float[] {0.0F, 0.0F, 0.0F, 0.0F};
		}
		return new float[] {
			(float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255),
			(float) visible / pixelCount
		};
	}
}
