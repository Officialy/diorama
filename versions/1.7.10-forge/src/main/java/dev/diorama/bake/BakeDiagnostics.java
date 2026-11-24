package dev.diorama.bake;

/** Legacy diagnostic sink with no game dependency, also usable by offline probes. */
final class BakeDiagnostics {

    private BakeDiagnostics() {}

    static void partial(TileKey key, int blocks, StringBuilder missing) {
        System.err.println("Partial bake of " + key + " (" + blocks + " blocks) hit unknown chunks:" + missing);
    }
}
