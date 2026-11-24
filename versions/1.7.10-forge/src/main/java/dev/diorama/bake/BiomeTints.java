package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;

/** Prepared tints are already blended; all averaging belongs to the shared TintBlend core. */
public final class BiomeTints {

    private BiomeTints() {}

    public static ChunkSnapshot.Tints of(ChunkSnapshot snapshot) {
        return snapshot.bakeTints();
    }
}
