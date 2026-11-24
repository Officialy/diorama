package dev.diorama.gtnh.diagnostic;

/** Deterministic ABGR input, independent of Minecraft and OpenGL. Not a map baker. */
public final class ProbePixels {

    public static final int SIZE = 128;

    private ProbePixels() {}

    public static int[] abgr() {
        int[] pixels = new int[SIZE * SIZE];
        int[] colours = { 0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFFFFFFFF };
        int[] alphas = { 0, 64, 128, 255 };
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int pixel = y < 96 ? colours[(y < 48 ? 0 : 2) + (x < 64 ? 0 : 1)] : (alphas[x / 32] << 24) | 0x000000FF;
                // Asymmetric top-left L detects flipped U/V axes.
                if ((x >= 4 && x < 8 && y >= 4 && y < 20) || (x >= 4 && x < 20 && y >= 16 && y < 20))
                    pixel = 0xFF000000;
                pixels[y * SIZE + x] = pixel;
            }
        }
        return pixels;
    }

    public static int argb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255);
    }

    public static void copyToArgb(int[] abgr, int[] destination) {
        if (abgr.length != destination.length) throw new IllegalArgumentException("Pixel sizes differ");
        for (int i = 0; i < abgr.length; i++) destination[i] = argb(abgr[i]);
    }
}
