package dev.diorama.gtnh.client;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.WorldProviderEnd;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.MapDimensionIdentity;

/** Dimension classification is based on the audited storage height, not the provider class. */
final class ClientMapDimension {

    private final int id;
    private final String suffix;
    private final boolean surface;

    private ClientMapDimension(int id, String suffix, boolean surface) {
        this.id = id;
        this.suffix = suffix;
        this.surface = surface;
    }

    int id() {
        return id;
    }

    String suffix() {
        return suffix;
    }

    boolean surfaceTerrain() {
        return surface;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClientMapDimension dimension && id == dimension.id
            && surface == dimension.surface
            && suffix.equals(dimension.suffix);
    }

    @Override
    public int hashCode() {
        return 31 * id + suffix.hashCode();
    }

    static @Nullable ClientMapDimension of(@Nullable WorldClient world) {
        if (world == null || world.provider == null || !MapDimensionIdentity.supportsHeight(world.getHeight()))
            return null;
        int id = world.provider.dimensionId;
        boolean noSky = world.provider.hasNoSky;
        boolean end = world.provider instanceof WorldProviderEnd;
        return new ClientMapDimension(
            id,
            MapDimensionIdentity.suffix(id, noSky, end),
            MapDimensionIdentity.surface(id, noSky, end));
    }
}
