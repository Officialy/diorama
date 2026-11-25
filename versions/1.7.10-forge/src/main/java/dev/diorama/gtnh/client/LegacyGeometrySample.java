package dev.diorama.gtnh.client;

import org.jspecify.annotations.Nullable;

/** Detached per-renderer-thread scratch data; never retains world or texture references. */
final class LegacyGeometrySample {

    final float[] vertices;
    @Nullable Object token;
    long key;
    int x, y, z, count;
    double u, v;
    boolean active, directUv, explicitUv;

    LegacyGeometrySample(int capacity) {
        vertices = new float[capacity * 5];
    }
}
