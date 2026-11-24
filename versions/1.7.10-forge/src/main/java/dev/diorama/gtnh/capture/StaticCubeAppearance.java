package dev.diorama.gtnh.capture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import dev.diorama.bake.PreparedBlockLooks;

/** Owner-thread, all-or-nothing preparation of the static opaque cube subset. */
public final class StaticCubeAppearance {

    private StaticCubeAppearance() {}

    public interface Access {

        boolean eligible();

        float[] average(int side) throws IOException;
    }

    /** Every mod/resource callback completes before publication; unknown never allocates a fallback. */
    public static int prepare(PreparedBlockLooks looks, int rawId, int metadata, Access access,
        BooleanSupplier current) {
        float[] top = null, side = null;
        boolean accepted = false;
        try {
            if (!current.getAsBoolean() || !access.eligible() || !current.getAsBoolean()) return -1;
            top = access.average(1);
            if (!current.getAsBoolean() || top == null) return -1;
            side = access.average(3);
            if (!current.getAsBoolean() || side == null) return -1;
        } catch (IOException | RuntimeException | LinkageError unsupported) {
            return -1;
        } finally {
            accepted = current.getAsBoolean();
        }
        if (!accepted) return -1;
        return looks.cube(rawId, metadata, top, side, 0);
    }

    /** MCP/SRG aliases resolve against the actual base class, never by subclass name alone. */
    public static boolean inherits(Class<?> type, Class<?> base, String[] aliases, Class<?>... parameters) {
        if (!base.isAssignableFrom(type)) return false;
        boolean found = false;
        try {
            for (String alias : aliases) {
                Method method;
                try {
                    method = base.getDeclaredMethod(alias, parameters);
                } catch (NoSuchMethodException absentAlias) {
                    continue;
                }
                found = true;
                if (type.getMethod(method.getName(), parameters)
                    .getDeclaringClass() != base) return false;
            }
            return found;
        } catch (ReflectiveOperationException | SecurityException | LinkageError unsupported) {
            return false;
        }
    }

    /** No mutation of the globally shared Block bounds just to test eligibility. */
    public static boolean unitBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return minX == 0 && minY == 0 && minZ == 0 && maxX == 1 && maxY == 1 && maxZ == 1;
    }
}
