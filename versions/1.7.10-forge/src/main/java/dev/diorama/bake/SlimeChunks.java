package dev.diorama.bake;

import java.util.Random;

/** Vanilla 1.7.10 Chunk.getRandomWithSeed: int products overflow before widening. */
final class SlimeChunks {

    private SlimeChunks() {}

    static boolean slimeChunk(long seed, int chunkX, int chunkZ) {
        long mixed = seed + (long) (chunkX * chunkX * 4987142)
            + (long) (chunkX * 5947611)
            + (long) (chunkZ * chunkZ) * 4392871L
            + (long) (chunkZ * 389711);
        return new Random(mixed ^ 987234911L).nextInt(10) == 0;
    }
}
