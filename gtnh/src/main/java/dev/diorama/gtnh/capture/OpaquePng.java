package dev.diorama.gtnh.capture;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.SpriteAverage;

/** Restricted static opaque PNG path, not a general atlas/custom-texture implementation. */
public final class OpaquePng {

    private OpaquePng() {}

    /** Caller owns the stream. Unsupported format/dimensions/transparency returns pending (null). */
    public static float @Nullable [] average(InputStream input, int width, int height) throws IOException {
        // This deliberately restricted path admits at most a 4096-square individual sprite.
        if (width <= 0 || width > 4096 || width != height) return null;
        BufferedInputStream stream = new BufferedInputStream(input);
        stream.mark(8);
        int[] signature = { 137, 80, 78, 71, 13, 10, 26, 10 };
        for (int value : signature) {
            if (stream.read() != value) return null;
        }
        stream.reset();
        // Inspect dimensions before allocating decoded pixels. Closing our cache never closes
        // the caller-owned source stream; no global ImageIO cache setting is changed.
        try (MemoryCacheImageInputStream cache = new MemoryCacheImageInputStream(stream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(cache);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(cache);
                if (reader.getWidth(0) != width || reader.getHeight(0) != height) return null;
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
            if (image == null) return null;
            try {
                return average(image, width, height);
            } finally {
                image.flush();
            }
        }
    }

    private static float @Nullable [] average(BufferedImage image, int width, int height) {
        if (image.getWidth() != width || image.getHeight() != height) return null;
        SpriteAverage average = new SpriteAverage();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                // A resource pack can punch holes in a normally opaque vanilla cube. Do not
                // retain its solid geometry, even when all alpha exceeds 26.
                if ((argb >>> 24) != 255) return null;
                average.add(argb);
            }
        }
        return average.finish(Math.multiplyExact(width, height));
    }
}
