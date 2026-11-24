package dev.diorama.gtnh.client;

import net.minecraft.world.chunk.Chunk;

import com.falsepattern.endlessids.config.GeneralConfig;
import com.falsepattern.endlessids.mixin.helpers.ChunkBiomeHook;

import dev.diorama.gtnh.capture.TintCapture;

/** Optional bridge, reached only after the audited EndlessIDs version was admitted. */
final class EndlessBiomeStorage {

    private EndlessBiomeStorage() {}

    static boolean enabled() {
        return GeneralConfig.extendBiome;
    }

    static int[] read(Chunk chunk, boolean extended) {
        if (GeneralConfig.extendBiome != extended || (chunk instanceof ChunkBiomeHook) != extended)
            throw new UnsupportedOperationException("EndlessIDs biome configuration and applied hooks disagree");
        // The biome extension is independent of extendBlockItem. Never touch the disabled byte API.
        return extended ? TintCapture.biomes(((ChunkBiomeHook) chunk).getBiomeShortArray())
            : TintCapture.biomes(chunk.getBiomeArray());
    }
}
