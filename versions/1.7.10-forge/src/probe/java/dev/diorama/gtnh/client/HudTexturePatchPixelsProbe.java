package dev.diorama.gtnh.client;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/** Exercises exact CPU words submitted to glTexSubImage2D, without Minecraft or GL. */
public final class HudTexturePatchPixelsProbe {

    private HudTexturePatchPixelsProbe() {}

    public static void main(String[] args) throws Exception {
        int[] abgr = { 0xDEADBEEF, 0x76543210, 0x12345678, 0xFFFFFF00, 0xFFFFFFAA, 0xFFFFFF56, 0xFFFF0000, 0x804080C0,
            0xFE112233, 0xFFFFFFFF, 0xFF0000FF, 0x00FF0000, 0x5500FF00, 0xFF000000, 0xDECAFBAD };
        int[] before = abgr.clone();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        check(
            digest,
            HudTexturePatchPixels.pack(abgr, 5, 3, 1, 1, 3, 2, false),
            0xFF0000FF,
            0x80C08040,
            0xFE332211,
            0x000000FF,
            0x5500FF00,
            0xFF000000);
        check(
            digest,
            HudTexturePatchPixels.pack(abgr, 5, 3, 1, 1, 3, 2, true),
            0xFF1C00B2,
            0x808C9366,
            0xFE25271B,
            0x001C00B2,
            0x5596B200,
            0xFF000000);
        check(digest, HudTexturePatchPixels.pack(abgr, 5, 3, 0, 0, 1, 3, false), 0xDEEFBEAD, 0xFF56FFFF, 0xFFFF0000);
        check(digest, HudTexturePatchPixels.pack(abgr, 5, 3, 4, 2, 1, 1, false), 0xDEADFBCA);
        check(
            digest,
            HudTexturePatchPixels.pack(abgr, 5, 3, 0, 0, 5, 1, false),
            0xDEEFBEAD,
            0x76103254,
            0x12785634,
            0xFF00FFFF,
            0xFFAAFFFF);
        require(Arrays.equals(before, abgr), "packing never modifies source");

        int[][] invalid = { { 0, 3, 0, 0, 1, 1 }, { 3, 5, 0, 0, 2, 0 }, { 4, 3, 0, 0, 1, 1 }, { 5, 3, -1, 0, 1, 1 },
            { 5, 3, 0, -1, 1, 1 }, { 5, 3, 3, 0, 3, 1 }, { 5, 3, 0, 2, 1, 2 }, { 5, 3, Integer.MAX_VALUE, 0, 1, 1 },
            { 5, 3, 1, 0, Integer.MAX_VALUE, 1 }, { Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 1, 1 } };
        for (int[] bounds : invalid) {
            try {
                HudTexturePatchPixels
                    .pack(abgr, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], false);
                throw new AssertionError("accepted invalid bounds: " + Arrays.toString(bounds));
            } catch (IllegalArgumentException expected) {
                digest.update((byte) 1);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println("PASS HUD patch rectangle packing, ABGR/ARGB and vanilla anaglyph; native GL UNTESTED");
    }

    private static void check(MessageDigest digest, IntBuffer packed, int... words) {
        require(packed.isDirect(), "client upload uses direct memory");
        require(packed.order() == ByteOrder.nativeOrder(), "packed integer uses native byte order");
        require(
            packed.position() == 0 && packed.remaining() == words.length && packed.capacity() == words.length,
            "buffer is tightly packed and ready to read");
        for (int i = 0; i < words.length; i++) {
            int word = packed.get();
            require(word == words[i], "unexpected word " + i + ": " + Integer.toHexString(word));
            for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (word >>> shift));
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
