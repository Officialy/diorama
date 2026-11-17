package dev.diorama.gtnh.capture;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.SpriteAverage;

/** Water-only static sampling of an ordinary PNG, not general animation or custom-loader support. */
public final class WaterPng {

    private WaterPng() {}

    /**
     * The caller owns the stream. Matches canonical top-left sprite-sized original-image sampling,
     * not the animation's first scheduled frame, interpolation, or a mean over its timeline. Legacy
     * ordinary sprites are square, with animation frames in a vertical strip. Source headers are
     * bounded before decoding only that first square; no global ImageIO cache setting is changed.
     */
    public static float @Nullable [] averageFirstFrame(InputStream input, int width, int height, boolean animated)
        throws IOException {
        if (width <= 0 || width > 4096 || width != height) return null;
        BufferedInputStream stream = new BufferedInputStream(input);
        stream.mark(8);
        int[] signature = { 137, 80, 78, 71, 13, 10, 26, 10 };
        for (int value : signature) if (stream.read() != value) return null;
        stream.reset();
        try (MemoryCacheImageInputStream cache = new MemoryCacheImageInputStream(stream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(cache);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            BufferedImage image;
            try {
                reader.setInput(cache);
                int sourceWidth = reader.getWidth(0), sourceHeight = reader.getHeight(0);
                if (sourceWidth != width || sourceHeight < height
                    || sourceHeight > 65536
                    || sourceHeight % height != 0
                    || (!animated && sourceHeight != height)
                    || (long) sourceWidth * sourceHeight > 67108864L) return null;
                ImageReadParam region = reader.getDefaultReadParam();
                region.setSourceRegion(new Rectangle(0, 0, width, height));
                image = reader.read(0, region);
            } finally {
                reader.dispose();
            }
            if (image == null) return null;
            try {
                if (image.getWidth() != width || image.getHeight() != height) return null;
                SpriteAverage average = new SpriteAverage();
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) average.add(image.getRGB(x, y));
                // WATER ignores texture alpha as geometry/opacity. SpriteAverage's visibility
                // threshold and alpha-weighted stored-sRGB are authoritative, including partial alpha.
                return average.finish(Math.multiplyExact(width, height));
            } finally {
                image.flush();
            }
        }
    }
}
