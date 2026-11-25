package dev.diorama.gtnh.capture;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/** Pure bounded geometry reduction for detached textured quads (x, y, z, u, v per vertex). */
public final class LegacyGeometryMath {

    private LegacyGeometryMath() {}

    /** Match horizontal quad footprints into cuboids; otherwise retain a coarse enclosing box. */
    public static float @Nullable [] bounds(float[] vertices) {
        if (vertices.length < 40 || vertices.length > 1280 || vertices.length % 20 != 0) return null;
        int count = vertices.length / 5;
        for (int i = 0; i < vertices.length; i++) {
            if (!Float.isFinite(vertices[i]) || vertices[i] < 0 || vertices[i] > 1) return null;
        }
        float[] all = { 1, 1, 1, 0, 0, 0 };
        float[] faces = new float[count / 4 * 6];
        int faceCount = 0;
        for (int q = 0; q < count; q += 4) {
            float[] face = { 1, 1, 1, 0, 0, 0 };
            for (int v = q; v < q + 4; v++) {
                for (int axis = 0; axis < 3; axis++) {
                    float value = vertices[v * 5 + axis];
                    face[axis] = Math.min(face[axis], value);
                    face[axis + 3] = Math.max(face[axis + 3], value);
                    all[axis] = Math.min(all[axis], value);
                    all[axis + 3] = Math.max(all[axis + 3], value);
                }
            }
            if (face[1] == face[4] && face[0] < face[3] && face[2] < face[5]) {
                System.arraycopy(face, 0, faces, faceCount++ * 6, 6);
            }
        }
        if (!volume(all)) return null;
        float[] result = new float[96];
        float[] covered = { 1, 1, 1, 0, 0, 0 };
        int used = 0;
        for (int a = 0; a < faceCount; a++) {
            int i = a * 6;
            int partner = -1;
            float distance = Float.POSITIVE_INFINITY;
            for (int b = 0; b < faceCount; b++) {
                int j = b * 6;
                float delta = faces[j + 1] - faces[i + 1];
                if (delta > 0 && delta < distance
                    && faces[i] == faces[j]
                    && faces[i + 2] == faces[j + 2]
                    && faces[i + 3] == faces[j + 3]
                    && faces[i + 5] == faces[j + 5]) {
                    partner = j;
                    distance = delta;
                }
            }
            if (partner < 0) continue;
            if (used == result.length) return all;
            System.arraycopy(faces, i, result, used, 6);
            result[used + 4] = faces[partner + 1];
            for (int axis = 0; axis < 3; axis++) {
                covered[axis] = Math.min(covered[axis], result[used + axis]);
                covered[axis + 3] = Math.max(covered[axis + 3], result[used + axis + 3]);
            }
            used += 6;
        }
        return used > 0 && Arrays.equals(all, covered) ? Arrays.copyOf(result, used) : all;
    }

    private static boolean volume(float[] box) {
        return box[0] < box[3] && box[1] < box[4] && box[2] < box[5];
    }

    /** Representative metadata-only outlines for legacy built-in render types. Null means full cube. */
    public static float @Nullable [] basicBoxes(int type, int metadata) {
        float thin = 1F / 16;
        return switch (type) {
            case 2 -> new float[] { 0.4375F, 0, 0.4375F, 0.5625F, 0.625F, 0.5625F };
            case 5, 9, 28 -> new float[] { 0, 0, 0, 1, thin, 1 };
            case 7 -> {
                // Upper halves do not encode orientation; this is a representative thin door.
                if ((metadata & 8) != 0) yield new float[] { 0, 0, 0.40625F, 1, 1, 0.59375F };
                int facing = metadata & 3;
                if ((metadata & 4) != 0) facing = (facing + 1) & 3;
                yield switch (facing) {
                    case 0 -> new float[] { 0, 0, 0, 0.1875F, 1, 1 };
                    case 1 -> new float[] { 0, 0, 0, 1, 1, 0.1875F };
                    case 2 -> new float[] { 0.8125F, 0, 0, 1, 1, 1 };
                    default -> new float[] { 0, 0, 0.8125F, 1, 1, 1 };
                };
            }
            case 8 -> switch (metadata) {
                    case 2 -> new float[] { 0, 0, 0.875F, 1, 1, 1 };
                    case 3 -> new float[] { 0, 0, 0, 1, 1, 0.125F };
                    case 4 -> new float[] { 0.875F, 0, 0, 1, 1, 1 };
                    case 5 -> new float[] { 0, 0, 0, 0.125F, 1, 1 };
                    default -> null;
                };
            case 11 -> new float[] { 0.375F, 0, 0.375F, 0.625F, 1, 0.625F };
            case 13 -> new float[] { thin, 0, thin, 1 - thin, 1, 1 - thin };
            case 14 -> new float[] { 0, 0, 0, 1, 0.5625F, 1 };
            case 15, 34 -> new float[] { 0, 0, 0, 1, 0.125F, 1 };
            case 18 -> new float[] { 0.4375F, 0, 0, 0.5625F, 1, 1, 0, 0, 0.4375F, 1, 1, 0.5625F };
            case 21 -> (metadata & 1) == 0 ? new float[] { 0, 0.25F, 0.375F, 1, 1, 0.625F }
                : new float[] { 0.375F, 0.25F, 0, 0.625F, 1, 1 };
            case 22 -> new float[] { thin, 0, thin, 1 - thin, 0.875F, 1 - thin };
            case 23 -> new float[] { 0, 0, 0, 1, thin, 1 };
            case 24 -> new float[] { 0, 0, 0, 1, 0.3125F, 1, 0, 0.3125F, 0, 0.125F, 1, 1, 0.875F, 0.3125F, 0, 1, 1, 1,
                0.125F, 0.3125F, 0, 0.875F, 1, 0.125F, 0.125F, 0.3125F, 0.875F, 0.875F, 1, 1 };
            case 32 -> new float[] { 0.25F, 0, 0.25F, 0.75F, 1, 0.75F };
            case 35 -> new float[] { 0, 0.625F, 0, 1, 1, 1, 0.25F, 0.25F, 0.25F, 0.75F, 0.625F, 0.75F, 0.375F, 0,
                0.375F, 0.625F, 0.25F, 0.625F };
            default -> null;
        };
    }

    /** Returns UV rectangle and geometric projected areas (top, side). */
    public static float[] quad(float[] vertices, int offset) {
        if (offset < 0 || offset % 20 != 0 || offset > vertices.length - 20)
            throw new IllegalArgumentException("Invalid quad offset");
        float[] result = { 1, 1, 0, 0, 0, 0 };
        for (int i = offset; i < offset + 20; i += 5) {
            result[0] = Math.min(result[0], vertices[i + 3]);
            result[1] = Math.min(result[1], vertices[i + 4]);
            result[2] = Math.max(result[2], vertices[i + 3]);
            result[3] = Math.max(result[3], vertices[i + 4]);
        }
        // Two triangles, independent of winding. Project the cross products onto the map's
        // horizontal and vertical faces rather than inferring normals from texture orientation.
        addArea(vertices, offset, offset + 5, offset + 10, result);
        addArea(vertices, offset, offset + 10, offset + 15, result);
        return result;
    }

    private static void addArea(float[] v, int a, int b, int c, float[] result) {
        float ax = v[b] - v[a], ay = v[b + 1] - v[a + 1], az = v[b + 2] - v[a + 2];
        float bx = v[c] - v[a], by = v[c + 1] - v[a + 1], bz = v[c + 2] - v[a + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        result[4] += Math.abs(ny) * 0.5F;
        result[5] += (Math.abs(nx) + Math.abs(nz)) * 0.5F;
    }
}
