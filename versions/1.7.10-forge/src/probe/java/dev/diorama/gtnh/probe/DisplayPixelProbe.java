package dev.diorama.gtnh.probe;

import java.security.MessageDigest;
import java.util.Arrays;

import dev.diorama.gtnh.diagnostic.ProbePixels;

/** CPU-side channel/orientation oracle; this does NOT exercise a GL context. */
public final class DisplayPixelProbe {

    public static void main(String[] arguments) throws Exception {
        int[] abgr = ProbePixels.abgr();
        int[] before = abgr.clone();
        require(abgr.length == 128 * 128, "size");
        require(at(abgr, 32, 24) == 0xFF0000FF, "top-left red");
        require(at(abgr, 96, 24) == 0xFF00FF00, "top-right green");
        require(at(abgr, 32, 72) == 0xFFFF0000, "middle-left blue");
        require(at(abgr, 96, 72) == 0xFFFFFFFF, "middle-right white");
        require(at(abgr, 4, 4) == 0xFF000000 && at(abgr, 19, 19) == 0xFF000000, "L marker");
        int[] alphas = { 0, 64, 128, 255 };
        for (int band = 0; band < 4; band++) {
            require(at(abgr, band * 32 + 16, 112) == (alphas[band] << 24 | 255), "alpha band");
        }
        require(ProbePixels.argb(0xAB123456) == 0xAB563412, "channel order");
        for (int i = 0; i < 4096; i++) {
            int pixel = i * 0x9E3779B9;
            require(ProbePixels.argb(ProbePixels.argb(pixel)) == pixel, "channel roundtrip");
        }
        int[] converted = new int[abgr.length];
        ProbePixels.copyToArgb(abgr, converted);
        require(Arrays.equals(before, abgr), "input unchanged");
        require(Arrays.equals(abgr, ProbePixels.abgr()), "deterministic fixture");
        require(at(converted, 32, 24) == 0xFFFF0000 && at(converted, 32, 72) == 0xFF0000FF, "legacy ARGB consumer");
        try {
            ProbePixels.copyToArgb(abgr, new int[1]);
            throw new AssertionError("size mismatch accepted");
        } catch (IllegalArgumentException expected) {
            // Do not upload partially initialized data.
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int pixel : converted) {
            digest.update((byte) (pixel >>> 24));
            digest.update((byte) (pixel >>> 16));
            digest.update((byte) (pixel >>> 8));
            digest.update((byte) pixel);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println("PASS display pixel inputs; GL UNTESTED; Java=" + System.getProperty("java.version"));
    }

    private static int at(int[] pixels, int x, int y) {
        return pixels[y * ProbePixels.SIZE + x];
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
