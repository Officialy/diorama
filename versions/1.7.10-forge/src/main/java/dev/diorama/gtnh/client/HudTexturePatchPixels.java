package dev.diorama.gtnh.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import dev.diorama.gtnh.diagnostic.ProbePixels;

/** CPU-side equivalent of vanilla's DynamicTexture upload, restricted to one rectangle. */
final class HudTexturePatchPixels {

    private HudTexturePatchPixels() {}

    static IntBuffer pack(int[] abgr, int stride, int rows, int x, int y, int width, int height, boolean anaglyph) {
        validate(abgr, stride, rows, x, y, width, height);
        IntBuffer packed = ByteBuffer.allocateDirect(Math.multiplyExact(width * height, Integer.BYTES))
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();
        return fill(packed, abgr, stride, x, y, width, height, anaglyph);
    }

    static IntBuffer packInto(IntBuffer buffer, int[] abgr, int stride, int rows, int x, int y, int width,
        int height, boolean anaglyph) {
        validate(abgr, stride, rows, x, y, width, height);
        if (!buffer.isDirect() || buffer.order() != ByteOrder.nativeOrder() || buffer.capacity() < width * height)
            throw new IllegalArgumentException("Invalid reusable upload buffer");
        buffer.clear();
        return fill(buffer, abgr, stride, x, y, width, height, anaglyph);
    }

    private static void validate(int[] abgr, int stride, int rows, int x, int y, int width, int height) {
        if (stride <= 0 || rows <= 0
            || (long) stride * rows != abgr.length
            || x < 0
            || y < 0
            || width <= 0
            || height <= 0
            || x > stride - width
            || y > rows - height) throw new IllegalArgumentException("Invalid HUD texture patch bounds");
    }

    private static IntBuffer fill(IntBuffer packed, int[] abgr, int stride, int x, int y, int width, int height,
        boolean anaglyph) {
        // TextureUtil uploads native integer 0xAARRGGBB words as BGRA / UNSIGNED_INT_8_8_8_8_REV.
        // It places source row zero at texture y=0; the patch must not reverse the row order either.
        for (int row = y; row < y + height; row++) {
            int start = row * stride + x;
            for (int i = start; i < start + width; i++) {
                int argb = ProbePixels.argb(abgr[i]);
                packed.put(anaglyph ? anaglyph(argb) : argb);
            }
        }
        packed.flip();
        return packed;
    }

    private static int anaglyph(int argb) {
        // Same channel weights and integer truncation as 1.7.10 TextureUtil.updateAnaglyph.
        int red = (argb >>> 16) & 255;
        int green = (argb >>> 8) & 255;
        int blue = argb & 255;
        int grey = (red * 30 + green * 59 + blue * 11) / 100;
        int outGreen = (red * 30 + green * 70) / 100;
        int outBlue = (red * 30 + blue * 70) / 100;
        return (argb & 0xFF000000) | (grey << 16) | (outGreen << 8) | outBlue;
    }
}
