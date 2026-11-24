package dev.diorama.gtnh.capture;

import java.util.Objects;

/** Per-read context, never a coordinate cache or a synthetic metadata value. */
public final class GrassContext {

    public static final int UNKNOWN = -1;
    public static final int NORMAL = 1;
    public static final int SNOWY = 2;

    private GrassContext() {}

    public interface Source {

        boolean onThread();

        boolean current();

        Object session();

        /** Raw storage ID, or -1 when the already-loaded data is unavailable. Never load a chunk. */
        int rawBlockId(int x, int y, int z);

        /** Prove registry identity and classify snow material; unknown raw IDs must return UNKNOWN. */
        int snowContext(int rawId);
    }

    public static int resolve(Source source, Object token, int x, int y, int z) {
        Objects.requireNonNull(source, "source");
        if (!source.onThread()) throw new IllegalStateException("Grass context requires the source thread");
        Objects.requireNonNull(token, "token");
        if (y < 0 || y > 255) throw new IllegalArgumentException("Invalid grass context span");
        if (!valid(source, token)) return UNKNOWN;
        // The standard legacy world has no block above y=255; do not wrap into section zero.
        if (y == 255) return NORMAL;
        int above = source.rawBlockId(x, y + 1, z);
        if (!valid(source, token)) return UNKNOWN;
        if (above < -1) throw new IllegalArgumentException("Invalid raw context ID");
        if (above == -1) return UNKNOWN;
        int context = source.snowContext(above);
        if (!valid(source, token)) return UNKNOWN;
        if (context != UNKNOWN && context != NORMAL && context != SNOWY)
            throw new IllegalArgumentException("Invalid snow material classification");
        return context;
    }

    private static boolean valid(Source source, Object token) {
        return source.current() && source.session() == token && source.current();
    }
}
