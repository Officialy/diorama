package dev.diorama.gtnh.client;

import net.minecraft.world.biome.BiomeGenBase;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.TintCapture;

/** Main-thread biome colour callbacks; no world, chunk or biome object enters the blended data. */
final class ClientBiomeColors implements TintCapture.Source {

    private final ClientCaptureAccess access;

    ClientBiomeColors(ClientCaptureAccess access) {
        this.access = access;
    }

    @Override
    public boolean onThread() {
        return this.access.onThread();
    }

    @Override
    public boolean current() {
        return this.access.current();
    }

    @Override
    public Object session() {
        return this.access.session();
    }

    @Override
    public int @Nullable [] biomes(int chunkX, int chunkZ) {
        return this.access.biomes(chunkX, chunkZ);
    }

    @Override
    public int @Nullable [] colors(int id, int x, int y, int z) {
        if (!this.onThread()) throw new IllegalStateException("Biome colours require the Minecraft main thread");
        if (!this.current()) return null;
        BiomeGenBase[] registry = BiomeGenBase.getBiomeGenArray();
        if (id < 0 || id >= registry.length) return null;
        BiomeGenBase biome = registry[id];
        // Direct lookup preserves missing/unregistered IDs instead of getBiome() fallback to ocean.
        if (biome == null || biome.biomeID != id) return null;
        int grass = biome.getBiomeGrassColor(x, y, z);
        if (!this.same(registry, id, biome)) return null;
        int foliage = biome.getBiomeFoliageColor(x, y, z);
        if (!this.same(registry, id, biome)) return null;
        int water = biome.getWaterColorMultiplier();
        if (!this.same(registry, id, biome)) return null;
        return new int[] { grass, foliage, water };
    }

    private boolean same(BiomeGenBase[] registry, int id, BiomeGenBase biome) {
        return this.current() && BiomeGenBase.getBiomeGenArray() == registry && registry[id] == biome;
    }
}
