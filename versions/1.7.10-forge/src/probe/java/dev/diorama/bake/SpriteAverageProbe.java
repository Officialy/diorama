package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/** Standalone numeric checks of the shared accumulator; no Minecraft, image, GL or world types. */
public final class SpriteAverageProbe {

    private static final MessageDigest HASH = digest();
    private static int cases;

    public static void main(String[] args) {
        boundaries();
        invalidCounts();
        lifecycle();
        for (int alpha = 0; alpha <= 255; alpha++) {
            for (int channel = 0; channel <= 255; channel++) {
                verify(new int[] { alpha << 24 | channel << 16 | (255 - channel) << 8 | 37 });
            }
        }
        Random random = new Random(0x535052495445L);
        for (int trial = 0; trial < 256; trial++) {
            int[] pixels = new int[1 + random.nextInt(4096)];
            for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
            verify(pixels);
        }
        int[] large = new int[131072];
        Arrays.fill(large, 0xFFFFFFFF);
        verify(large); // Integer channel*alpha sums would overflow here.
        for (int i = 0; i < large.length; i++) large[i] = random.nextInt();
        verify(large);
        int[] extremes = { 0, 1, 25, 26, 27, 127, 128, 254, 255 };
        int[] pixels = new int[extremes.length * 8];
        int at = 0;
        for (int alpha : extremes) for (int mask = 0; mask < 8; mask++) {
            pixels[at++] = alpha << 24 | ((mask & 1) == 0 ? 0 : 0xFF0000)
                | ((mask & 2) == 0 ? 0 : 0x00FF00)
                | ((mask & 4) == 0 ? 0 : 0x0000FF);
        }
        verify(pixels);
        StringBuilder hex = new StringBuilder();
        for (byte value : HASH.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS sprite average threshold/ARGB/sRGB/alpha weight/coverage/long oracle; cases=" + cases
                + "; game image reads, frame crop and freed-image handling UNTESTED");
    }

    private static void boundaries() {
        exact(new int[] { 0x19FFFFFF }, 0, 0, 0, 0);
        exact(new int[] { 0x1AFF0000 }, 1, 0, 0, 1);
        exact(new int[] { 0x1A00FF00 }, 0, 1, 0, 1);
        exact(new int[] { 0x1A0000FF }, 0, 0, 1, 1);
        exact(new int[] { 0xFF123456 }, 18 / 255.0F, 52 / 255.0F, 86 / 255.0F, 1);
        exact(new int[] { 0x00FFFFFF, 0x19FF00FF, 0xFF000000 }, 0, 0, 0, 1 / 3.0F);
        exact(new int[] { 0x00010203, 0x00FFFFFF }, 0, 0, 0, 0);
        exact(new int[] { 0x1A000000 }, 0, 0, 0, 1); // Black is visible, not missing.
        exact(new int[] { 0xFFFFFFFF, 0xFF000000 }, 0.5F, 0.5F, 0.5F, 1); // Stored sRGB, not linear light.
        exact(new int[] { 0xFFFF0000, 0x550000FF }, 0.75F, 0, 0.25F, 1);
        exact(new int[] { 0x1AFF0000, 0x1A00FF00, 0x000000FF }, 0.5F, 0.5F, 0, 2 / 3.0F);
        exact(new int[] { 0x1AFFFFFF, 0x1AFFFFFF }, 1, 1, 1, 1); // Coverage is not mean alpha.
    }

    private static void invalidCounts() {
        SpriteAverage empty = new SpriteAverage();
        SpriteAverage filled = new SpriteAverage();
        filled.add(0xFFFFFFFF);
        for (SpriteAverage average : new SpriteAverage[] { empty, filled }) {
            for (int count : new int[] { 0, -1, Integer.MIN_VALUE }) {
                boolean rejected = false;
                try {
                    average.finish(count);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                require(rejected, "non-positive pixelCount rejected, even without visible pixels");
                put(count);
                cases++;
            }
        }
        require(Arrays.equals(empty.finish(1), new float[4]), "empty positive area is transparent black");
    }

    private static void lifecycle() {
        SpriteAverage average = new SpriteAverage();
        average.add(0xFFFF0000);
        float[] first = average.finish(1);
        first[0] = -1;
        require(average.finish(1)[0] == 1, "finish returns a private array");
        average.add(0xFF0000FF);
        require(Arrays.equals(average.finish(2), new float[] { 0.5F, 0, 0.5F, 1 }), "finish does not consume state");
        require(average.finish(Integer.MAX_VALUE)[3] == 2.0F / Integer.MAX_VALUE, "positive count extreme");
        cases++;
    }

    private static void exact(int[] pixels, float r, float g, float b, float coverage) {
        float[] actual = verify(pixels);
        require(Arrays.equals(actual, new float[] { r, g, b, coverage }), "explicit boundary expectation");
    }

    private static float[] verify(int[] pixels) {
        SpriteAverage average = new SpriteAverage();
        put(pixels.length);
        for (int pixel : pixels) {
            average.add(pixel);
            put(pixel);
        }
        float[] actual = average.finish(pixels.length);
        require(actual.length == 4, "RGBA result length");
        float[] expected = oracle(pixels);
        float[] legacy = legacy(pixels);
        for (int channel = 0; channel < 4; channel++) {
            require(
                !Float.isNaN(actual[channel]) && actual[channel] >= 0 && actual[channel] <= 1,
                "finite normalized output");
            // Independent integer arithmetic cancels the common alpha/255 factor. Allow one float
            // ULP for the deliberately preserved double summation order, not an arbitrary epsilon.
            require(
                Math.abs(actual[channel] - expected[channel]) <= Math.ulp(expected[channel]),
                "long rational oracle");
            require(
                Float.floatToIntBits(actual[channel]) == Float.floatToIntBits(legacy[channel]),
                "original loop bit parity");
            put(Float.floatToIntBits(actual[channel]));
        }
        require(
            Float.floatToIntBits(actual[3]) == Float.floatToIntBits(expected[3]),
            "coverage exact, not alpha average");
        require(Arrays.equals(actual, average.finish(pixels.length)), "repeat finish deterministic");
        cases++;
        return actual;
    }

    private static float[] oracle(int[] pixels) {
        long[] channels = new long[3];
        long alphaSum = 0;
        int visible = 0;
        for (int pixel : pixels) {
            // Unsigned arithmetic decoding differs from the production bit shifts.
            long unsigned = pixel & 0xFFFFFFFFL;
            int alpha = (int) (unsigned / 16777216L);
            if (alpha <= 25) continue;
            alphaSum += alpha;
            visible++;
            channels[0] += (unsigned / 65536L % 256) * alpha;
            channels[1] += (unsigned / 256L % 256) * alpha;
            channels[2] += (unsigned % 256) * alpha;
        }
        float[] result = new float[4];
        if (alphaSum != 0) for (int channel = 0; channel < 3; channel++) {
            result[channel] = (float) ((double) channels[channel] / (alphaSum * 255L));
        }
        result[3] = (float) visible / pixels.length;
        return result;
    }

    private static float[] legacy(int[] pixels) {
        double r = 0, g = 0, b = 0, weight = 0;
        int visible = 0;
        for (int argb : pixels) {
            int a = argb >>> 24;
            if (a < 26) continue;
            double wa = a / 255.0;
            r += ((argb >> 16) & 0xFF) * wa;
            g += ((argb >> 8) & 0xFF) * wa;
            b += (argb & 0xFF) * wa;
            weight += wa;
            visible++;
        }
        if (weight == 0) return new float[4];
        return new float[] { (float) (r / weight / 255), (float) (g / weight / 255), (float) (b / weight / 255),
            (float) visible / pixels.length };
    }

    private static void put(int value) {
        HASH.update((byte) (value >>> 24));
        HASH.update((byte) (value >>> 16));
        HASH.update((byte) (value >>> 8));
        HASH.update((byte) value);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
