package dev.diorama.gtnh.capture;

import org.jspecify.annotations.Nullable;

/** Audited vanilla leaf species; decay flags do not change their map appearance. */
public final class LegacyLeaves {

    private static final String[] OLD = { "leaves_oak", "leaves_spruce", "leaves_birch", "leaves_jungle" };
    private static final String[] NEW = { "leaves_acacia", "leaves_big_oak" };

    private LegacyLeaves() {}

    /** Normal leaf sprites keep map colours independent of the game's fast/fancy graphics switch. */
    public static @Nullable String sprite(boolean newer, int metadata) {
        if (metadata < 0 || metadata > 15) return null;
        String[] species = newer ? NEW : OLD;
        int index = metadata & 3;
        return index < species.length ? species[index] : null;
    }

    /** Spruce and birch use the block's fixed render colour; other species use prepared biome foliage. */
    public static boolean fixedTint(boolean newer, int metadata) {
        return !newer && metadata >= 0 && metadata <= 15 && ((metadata & 3) == 1 || (metadata & 3) == 2);
    }
}
