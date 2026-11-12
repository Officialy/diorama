package dev.diorama.bake;

/** Modern logging boundary; keeps loader/logger types out of the shared tracer. */
final class BakeDiagnostics {
	private BakeDiagnostics() {
	}

	static void partial(TileKey key, int blocks, StringBuilder missing) {
		dev.diorama.Diorama.LOGGER.info("Partial bake of {} ({} blocks) hit unknown chunks:{}", key, blocks, missing);
	}
}
