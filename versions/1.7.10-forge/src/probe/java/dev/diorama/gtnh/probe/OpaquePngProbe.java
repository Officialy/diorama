package dev.diorama.gtnh.probe;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import dev.diorama.gtnh.capture.OpaquePng;

/** Real ImageIO PNG fixtures; no resource manager, Minecraft, atlas or GL context. */
public final class OpaquePngProbe {

    private static final MessageDigest HASH = digest();
    private static int cases;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        opaque();
        transparency();
        dimensions();
        boundedHeaders();
        formatsAndTruncation();
        failingStream();
        cutout();
        cutoutRestrictions();
        StringBuilder hex = new StringBuilder();
        for (byte value : HASH.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS opaque PNG channels/alpha/dimensions/signature/IO/stream ownership; cases=" + cases
                + "; assertions="
                + assertions
                + "; resource lookup, atlas, animation metadata and GL UNTESTED");
    }

    private static void opaque() throws IOException {
        for (int type : new int[] { BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB }) {
            for (int pixel : new int[] { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF000000, 0xFFFFFFFF, 0xFF123456 }) {
                byte[] data = png(1, 1, type, new int[] { pixel });
                float[] expected = { ((pixel >>> 16) & 255) / 255.0F, ((pixel >>> 8) & 255) / 255.0F,
                    (pixel & 255) / 255.0F, 1 };
                exact(attempt(data, 1, 1, false), expected, "opaque single-pixel ARGB channels");
            }
        }
        // Asymmetric non-primary channels catch swaps as well as averaging a single texel/row.
        int[] pixels = { 0xFF102030, 0xFF204060, 0xFF4080C0, 0xFF80A0E0 };
        byte[] data = png(2, 2, BufferedImage.TYPE_INT_ARGB, pixels);
        float[] expected = { 60 / 255.0F, 96 / 255.0F, 140 / 255.0F, 1 };
        float[] first = attempt(data, 2, 2, false);
        exact(first, expected, "all four pixels, stored sRGB arithmetic mean");
        first[0] = -1;
        exact(attempt(data, 2, 2, false), expected, "private deterministic result");
        exact(
            attempt(
                png(2, 2, BufferedImage.TYPE_INT_ARGB, new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF }),
                2,
                2,
                false),
            new float[] { 0.5F, 0.5F, 0.5F, 1 },
            "sRGB average, not linear light");
    }

    private static void transparency() throws IOException {
        // Each possible non-opaque alpha, including 25/26 and 254, must reject the entire texture.
        // Put it last so that already accumulated opaque pixels cannot leak a partial success.
        for (int alpha = 0; alpha < 255; alpha++) {
            int[] pixels = { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, alpha << 24 | 0x123456 };
            unsupported(png(2, 2, BufferedImage.TYPE_INT_ARGB, pixels), 2, 2);
        }
        // Hidden RGB is not evidence for a solid texture, nor may it be stripped to fake opaque black.
        for (int hiddenRgb : new int[] { 0, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0x123456 }) {
            unsupported(png(1, 1, BufferedImage.TYPE_INT_ARGB, new int[] { hiddenRgb }), 1, 1);
        }
        unsupported(
            png(2, 2, BufferedImage.TYPE_INT_ARGB, new int[] { 0x00FFFFFF, 0x00123456, 0x00000000, 0x00FF0000 }),
            2,
            2);
    }

    private static void dimensions() throws IOException {
        byte[] square = png(
            2,
            2,
            BufferedImage.TYPE_INT_ARGB,
            new int[] { 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000 });
        for (int[] size : new int[][] { { 1, 1 }, { 3, 3 }, { 0, 0 }, { -1, -1 },
            { Integer.MIN_VALUE, Integer.MIN_VALUE }, { 2, 1 }, { 1, 2 }, { 2, 0 }, { 0, 2 } }) {
            unsupported(square, size[0], size[1]);
        }
        // Both caller-side non-square requests and decoded-image mismatches are unsupported.
        byte[] wide = png(2, 1, BufferedImage.TYPE_INT_RGB, new int[] { 0xFFFF0000, 0xFF0000FF });
        unsupported(wide, 2, 1);
        unsupported(wide, 2, 2);
        byte[] tall = png(1, 2, BufferedImage.TYPE_INT_RGB, new int[] { 0xFF00FF00, 0xFFFFFFFF });
        unsupported(tall, 1, 2);
        unsupported(tall, 1, 1);
    }

    private static void boundedHeaders() throws IOException {
        for (int width : new int[] { 4097, 65536, Integer.MAX_VALUE }) {
            // Any read throws: oversized expected dimensions must reject before touching input.
            TrackingInput stream = new TrackingInput(new byte[] { 1 }, 0);
            try {
                require(OpaquePng.average(stream, width, width) == null, "oversized expected area rejected");
                require(stream.position == 0, "oversized expected dimensions perform zero reads");
            } finally {
                require(!stream.closed, "zero-read rejection preserves caller ownership");
            }
            put(width);
            cases++;
        }
        byte[] tiny = headerOnly(1, 1);
        // Positive control: this complete, CRC-correct IHDR passes dimension inspection, but
        // decoding must fail because there is no IDAT. The huge variants below must not decode.
        attempt(tiny, 1, 1, true);
        for (int[] size : new int[][] { { 65536, 65536 }, { Integer.MAX_VALUE, 1 }, { 1, Integer.MAX_VALUE } }) {
            unsupported(headerOnly(size[0], size[1]), 1, 1);
        }
        // The upper boundary itself is admitted for header inspection, not rejected like 4097.
        TrackingInput boundary = new TrackingInput(tiny, Integer.MAX_VALUE);
        try {
            require(OpaquePng.average(boundary, 4096, 4096) == null, "4096 request rejects only mismatched header");
            require(boundary.position > 0, "4096 upper boundary reaches header inspection");
        } finally {
            require(!boundary.closed, "header dimension rejection preserves caller ownership");
        }
        put(4096);
        cases++;
    }

    private static byte[] headerOnly(int width, int height) throws IOException {
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(ihdr);
        header.writeInt(0x49484452); // IHDR type participates in CRC.
        header.writeInt(width);
        header.writeInt(height);
        header.writeByte(8); // Eight bits per channel.
        header.writeByte(6); // RGBA, no palette required.
        header.writeByte(0); // Standard compression, filter and no interlace.
        header.writeByte(0);
        header.writeByte(0);
        header.flush();
        byte[] chunk = ihdr.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(chunk);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream png = new DataOutputStream(bytes);
        png.writeLong(0x89504E470D0A1A0AL);
        png.writeInt(13);
        png.write(chunk);
        png.writeInt((int) crc.getValue());
        png.flush();
        byte[] result = bytes.toByteArray();
        require(result.length == 33, "complete IHDR only, no IDAT or pixel allocation");
        put(width);
        put(height);
        put((int) crc.getValue());
        return result;
    }

    private static void formatsAndTruncation() throws IOException {
        byte[] valid = png(1, 1, BufferedImage.TYPE_INT_ARGB, new int[] { 0xFF123456 });
        unsupported(new byte[0], 1, 1);
        for (int length = 1; length < 8; length++) unsupported(Arrays.copyOf(valid, length), 1, 1);
        for (int position = 0; position < 8; position++) {
            byte[] wrong = valid.clone();
            wrong[position] ^= 1;
            unsupported(wrong, 1, 1);
        }
        unsupported(new byte[] { 'n', 'o', 't', '-', 'a', '-', 'p', 'n', 'g' }, 1, 1);
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            require(ImageIO.write(image, "bmp", bytes), "BMP fixture writer available");
            unsupported(bytes.toByteArray(), 1, 1); // ImageIO can read it; PNG-only admission cannot.
        } finally {
            image.flush();
        }
        // Full PNG signature admits decoding, but an absent/incomplete IHDR is an IO failure.
        for (int length : new int[] { 8, 16, 32 }) attempt(Arrays.copyOf(valid, length), 1, 1, true);
        byte[] fake = new byte[32];
        System.arraycopy(valid, 0, fake, 0, 8);
        attempt(fake, 1, 1, true);
    }

    private static void failingStream() throws IOException {
        byte[] data = png(1, 1, BufferedImage.TYPE_INT_ARGB, new int[] { 0xFFFFFFFF });
        for (int failAfter : new int[] { 0, 4, 8, 16 }) {
            TrackingInput stream = new TrackingInput(data, failAfter);
            boolean failed = false;
            try {
                OpaquePng.average(stream, 1, 1);
            } catch (IOException expected) {
                failed = true;
            } finally {
                require(!stream.closed, "IO failure leaves caller stream open");
            }
            require(failed, "source IOException propagates rather than returning success/pending");
            put(failAfter);
            cases++;
        }
    }

    private static void cutout() throws IOException {
        for (int pixel : new int[] { 0xFF123456, 0xFFFFFFFF, 0xFF000000, 0, 0x00123456, 0x00FFFFFF }) {
            cutoutPixels(1, new int[] { pixel });
        }
        int[] holes = { 0xFF102030, 0x00123456, 0xFF80A0E0, 0x00FFFFFF };
        float[] first = cutoutPixels(2, holes);
        exact(
            first,
            new float[] { 72 / 255.0F, 96 / 255.0F, 136 / 255.0F, 0.5F },
            "holes use visible colour and total coverage");
        holes[1] = 0x00FF0000;
        holes[3] = 0x0000FF00;
        exact(cutoutPixels(2, holes), first, "hidden RGB cannot affect colour or coverage");
        cutoutPixels(2, new int[] { 0x00FFFFFF, 0x00123456, 0, 0x00FF0000 });
        for (int alpha = 1; alpha < 255; alpha++) {
            // Includes 1/25/26/127/254, after valid visible and hidden pixels.
            byte[] data = png(
                2,
                2,
                BufferedImage.TYPE_INT_ARGB,
                new int[] { 0xFF123456, 0x00123456, 0xFFABCDEF, alpha << 24 | 0x654321 });
            require(attempt(data, 2, 2, false, true) == null, "cutout rejects every intermediate alpha");
        }
        Random random = new Random(0x4355544F55544CL);
        for (int size : new int[] { 1, 2, 3, 7, 16, 31 }) {
            for (int trial = 0; trial < 12; trial++) {
                int[] pixels = new int[size * size];
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = random.nextInt() & 0xFFFFFF | (random.nextBoolean() ? 0xFF000000 : 0);
                }
                cutoutPixels(size, pixels);
            }
        }
    }

    private static float[] cutoutPixels(int size, int[] pixels) throws IOException {
        byte[] data = png(size, size, BufferedImage.TYPE_INT_ARGB, pixels);
        float[] actual = attempt(data, size, size, false, true);
        exact(actual, binaryExpected(pixels), "binary PNG matches independent scalar oracle");
        boolean opaque = true;
        for (int pixel : pixels) opaque &= (pixel >>> 24) == 255;
        if (opaque) exact(attempt(data, size, size, false), actual, "opaque and cutout agree for opaque PNG");
        else unsupported(data, size, size);
        return actual;
    }

    // Binary-alpha oracle: integer visible-channel sums, independent of SpriteAverage.
    private static float[] binaryExpected(int[] pixels) {
        long red = 0, green = 0, blue = 0;
        int visible = 0;
        for (int pixel : pixels) {
            if ((pixel >>> 24) == 0) continue;
            require((pixel >>> 24) == 255, "oracle accepts only binary fixtures");
            red += (pixel >>> 16) & 255;
            green += (pixel >>> 8) & 255;
            blue += pixel & 255;
            visible++;
        }
        if (visible == 0) return new float[] { 0, 0, 0, 0 };
        return new float[] { (float) ((double) red / visible / 255), (float) ((double) green / visible / 255),
            (float) ((double) blue / visible / 255), (float) visible / pixels.length };
    }

    private static void cutoutRestrictions() throws IOException {
        boolean cache = ImageIO.getUseCache();
        byte[] square = png(2, 2, BufferedImage.TYPE_INT_ARGB, new int[] { 0xFF123456, 0, 0xFFABCDEF, 0x00123456 });
        for (int[] size : new int[][] { { 1, 1 }, { 3, 3 }, { 0, 0 }, { -1, -1 },
            { Integer.MIN_VALUE, Integer.MIN_VALUE }, { 2, 1 }, { 1, 2 }, { 2, 0 }, { 0, 2 } }) {
            require(attempt(square, size[0], size[1], false, true) == null, "cutout dimension mismatch rejected");
        }
        for (int[] size : new int[][] { { 2, 1 }, { 1, 2 } }) {
            byte[] rectangle = png(size[0], size[1], BufferedImage.TYPE_INT_ARGB, new int[] { 0xFF123456, 0 });
            require(attempt(rectangle, size[0], size[1], false, true) == null, "cutout rejects non-square request");
            require(attempt(rectangle, 1, 1, false, true) == null, "cutout checks both decoded dimensions");
        }
        for (int width : new int[] { 4097, 65536, Integer.MAX_VALUE }) {
            TrackingInput stream = new TrackingInput(new byte[] { 1 }, 0);
            require(OpaquePng.averageCutout(stream, width, width) == null, "cutout oversized request rejected");
            require(stream.position == 0 && !stream.closed, "cutout oversized request never reads or closes input");
            cases++;
            put(width);
        }
        byte[] tiny = headerOnly(1, 1);
        attempt(tiny, 1, 1, true, true); // Positive control: matching header reaches missing IDAT.
        for (int[] size : new int[][] { { 4097, 4097 }, { 65536, 65536 }, { Integer.MAX_VALUE, 1 },
            { 1, Integer.MAX_VALUE } }) {
            require(
                attempt(headerOnly(size[0], size[1]), 1, 1, false, true) == null,
                "cutout rejects huge header before pixel decoding");
        }
        TrackingInput boundary = new TrackingInput(tiny, Integer.MAX_VALUE);
        require(OpaquePng.averageCutout(boundary, 4096, 4096) == null, "cutout upper boundary inspects header");
        require(boundary.position > 0 && !boundary.closed, "cutout boundary reads header without taking ownership");
        require(attempt(new byte[0], 1, 1, false, true) == null, "cutout rejects missing signature");
        require(
            attempt(new byte[] { 'n', 'o', 't', '-', 'p', 'n', 'g' }, 1, 1, false, true) == null,
            "cutout rejects non-PNG signature");
        attempt(Arrays.copyOf(square, 16), 2, 2, true, true);
        for (int failAfter : new int[] { 0, 4, 8, 16 }) {
            TrackingInput stream = new TrackingInput(square, failAfter);
            boolean failed = false;
            try {
                OpaquePng.averageCutout(stream, 2, 2);
            } catch (IOException expected) {
                failed = true;
            } finally {
                require(!stream.closed, "cutout IO failure preserves stream ownership");
            }
            require(failed, "cutout propagates source IOException");
            put(failAfter);
            cases++;
        }
        require(ImageIO.getUseCache() == cache, "cutout does not change global ImageIO cache policy");
    }

    private static byte[] png(int width, int height, int type, int[] pixels) throws IOException {
        require(pixels.length == width * height, "fixture pixel count");
        BufferedImage image = new BufferedImage(width, height, type);
        try {
            image.setRGB(0, 0, width, height, pixels, 0, width);
            require(Arrays.equals(image.getRGB(0, 0, width, height, null, 0, width), pixels), "fixture ARGB preserved");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            require(ImageIO.write(image, "png", bytes), "PNG fixture writer available");
            // Hash logical fixtures, not JDK-dependent compression bytes.
            put(width);
            put(height);
            put(type);
            for (int pixel : pixels) put(pixel);
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static void unsupported(byte[] data, int width, int height) throws IOException {
        require(attempt(data, width, height, false) == null, "unsupported input is pending, not an opaque success");
    }

    private static float[] attempt(byte[] data, int width, int height, boolean expectIo) throws IOException {
        return attempt(data, width, height, expectIo, false);
    }

    private static float[] attempt(byte[] data, int width, int height, boolean expectIo, boolean cutout)
        throws IOException {
        TrackingInput stream = new TrackingInput(data, Integer.MAX_VALUE);
        float[] result = null;
        boolean failed = false;
        try {
            result = cutout ? OpaquePng.averageCutout(stream, width, height) : OpaquePng.average(stream, width, height);
        } catch (IOException expected) {
            if (!expectIo) throw expected;
            failed = true;
        } finally {
            require(!stream.closed, "caller stream remains open on every return/exception path");
        }
        require(failed == expectIo, "malformed admitted PNG raises IOException");
        require(stream.read() >= -1, "caller can still use stream after decode");
        put(width);
        put(height);
        // Do not hash encoded length: PNG compression can differ between JDK ImageIO writers.
        put(failed ? -2 : result == null ? -1 : result.length);
        if (result != null) for (float channel : result) put(Float.floatToIntBits(channel));
        cases++;
        return result;
    }

    private static void exact(float[] actual, float[] expected, String message) {
        require(actual != null && Arrays.equals(actual, expected), message);
    }

    private static final class TrackingInput extends InputStream {

        private final byte[] data;
        private final int failAfter;
        private int position;
        private boolean closed;

        private TrackingInput(byte[] data, int failAfter) {
            this.data = data;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            if (closed) throw new IOException("caller stream was closed");
            if (position >= failAfter) throw new IOException("injected read failure");
            return position == data.length ? -1 : data[position++] & 255;
        }

        @Override
        public int read(byte[] out, int offset, int length) throws IOException {
            if (closed) throw new IOException("caller stream was closed");
            if (length == 0) return 0;
            if (position >= failAfter) throw new IOException("injected read failure");
            if (position == data.length) return -1;
            int count = Math.min(length, Math.min(data.length - position, failAfter - position));
            System.arraycopy(data, position, out, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
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

    private static void require(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
