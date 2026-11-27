package dev.diorama.gtnh.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Client-wide display preferences, independent of map and world identity. */
final class MinimapOptions {

    static final int DEFAULT_SIZE = 96;
    private final Path file;
    boolean visible = true;
    boolean coordinates = true;
    boolean fog = Boolean.parseBoolean(System.getProperty("diorama.mapFog", "true"));
    boolean smoothZoom = Boolean.getBoolean("diorama.smoothZoom");
    boolean worldWaypoints = Boolean.parseBoolean(System.getProperty("diorama.worldWaypoints", "true"));
    boolean deathWaypoints = Boolean.parseBoolean(System.getProperty("diorama.deathWaypoints", "true"));
    boolean radar = Boolean.getBoolean("diorama.entityRadar");
    boolean playerRadar = Boolean.getBoolean("diorama.playerRadar");
    boolean beams = Boolean.getBoolean("diorama.waypointBeams");
    boolean volumetricFog = Boolean.parseBoolean(System.getProperty("diorama.volumetricFog", "true"));
    boolean highResolution = Boolean.parseBoolean(System.getProperty("diorama.highResolution", "true"));
    volatile int workers = clamp(Integer.getInteger("diorama.bakeWorkers", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()/4))), 1, 4);
    volatile int stride = clamp(Integer.getInteger("diorama.coarseStride", 4), 2, 8);
    volatile int captureMs = clamp(Integer.getInteger("diorama.captureBudgetMs", 4), 1, 12);
    volatile int captures = clamp(Integer.getInteger("diorama.capturesPerTick", 12), 1, 24);
    volatile int radius = clamp(Integer.getInteger("diorama.captureRadius", 0), 0, 32);
    volatile int resident = clamp(Integer.getInteger("diorama.residentTiles", 192), 96, 512);
    volatile int history = clamp(Integer.getInteger("diorama.historyTiles", 192), 0, 512);
    volatile int uploads = clamp(Integer.getInteger("diorama.uploadsPerFrame", 3), 1, 4);
    volatile boolean progressive = Boolean.parseBoolean(System.getProperty("diorama.progressiveBake", "true"));
    volatile boolean fast = Boolean.parseBoolean(System.getProperty("diorama.fastBake", "true"));
    volatile boolean gpuPreview = Boolean.parseBoolean(System.getProperty("diorama.gpuBake", "false"));
    volatile boolean gpuRefine = Boolean.parseBoolean(System.getProperty("diorama.gpuRefine", "false"));
    volatile boolean gpuFour = Boolean.parseBoolean(System.getProperty("diorama.gpuFourSamples", "false"));
    private int size = DEFAULT_SIZE;

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int number(Properties props, String key, int fallback, int min, int max) {
        try { return clamp(Integer.parseInt(props.getProperty(key, Integer.toString(fallback))), min, max); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    MinimapOptions(Path file) {
        this.file = file.toAbsolutePath();
    }

    int size() {
        return size;
    }

    void cycleSize() {
        size = size == 64 ? DEFAULT_SIZE : size == DEFAULT_SIZE ? 128 : 64;
    }

    void load() throws IOException {
        Properties props = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            props.load(input);
        } catch (NoSuchFileException missing) {
            return;
        }
        visible = !"false".equalsIgnoreCase(props.getProperty("visible"));
        coordinates = !"false".equalsIgnoreCase(props.getProperty("coordinates"));
        fog = Boolean.parseBoolean(props.getProperty("fog", Boolean.toString(fog)));
        smoothZoom = Boolean.parseBoolean(props.getProperty("smoothZoom", Boolean.toString(smoothZoom)));
        worldWaypoints = Boolean.parseBoolean(props.getProperty("worldWaypoints", Boolean.toString(worldWaypoints)));
        deathWaypoints = Boolean.parseBoolean(props.getProperty("deathWaypoints", Boolean.toString(deathWaypoints)));
        radar = Boolean.parseBoolean(props.getProperty("radar",Boolean.toString(radar)));
        playerRadar = Boolean.parseBoolean(props.getProperty("playerRadar",Boolean.toString(playerRadar)));
        beams = Boolean.parseBoolean(props.getProperty("beams",Boolean.toString(beams)));
        volumetricFog = Boolean.parseBoolean(props.getProperty("volumetricFog",Boolean.toString(volumetricFog)));
        highResolution = Boolean.parseBoolean(props.getProperty("highResolution",Boolean.toString(highResolution)));
        workers = number(props, "workers", workers, 1, 4);
        stride = number(props, "stride", stride, 2, 8);
        captureMs = number(props, "captureMs", captureMs, 1, 12);
        captures = number(props, "captures", captures, 1, 24);
        radius = number(props, "radius", radius, 0, 32);
        resident = number(props, "resident", resident, 96, 512);
        history = number(props, "history", history, 0, 512);
        uploads = number(props, "uploads", uploads, 1, 4);
        stride = stride == 2 || stride == 8 ? stride : 4;
        progressive = Boolean.parseBoolean(props.getProperty("progressive", Boolean.toString(progressive)));
        fast = Boolean.parseBoolean(props.getProperty("fast", Boolean.toString(fast)));
        gpuPreview = Boolean.parseBoolean(props.getProperty("gpuPreview", Boolean.toString(gpuPreview)));
        gpuRefine = Boolean.parseBoolean(props.getProperty("gpuRefine", Boolean.toString(gpuRefine)));
        gpuFour = Boolean.parseBoolean(props.getProperty("gpuFour", Boolean.toString(gpuFour)));
        String value = props.getProperty("size", "");
        size = "64".equals(value) ? 64 : "128".equals(value) ? 128 : DEFAULT_SIZE;
    }

    void save() throws IOException {
        Properties props = new Properties();
        props.setProperty("visible", Boolean.toString(visible));
        props.setProperty("coordinates", Boolean.toString(coordinates));
        props.setProperty("size", Integer.toString(size));
        props.setProperty("fog", Boolean.toString(fog));
        props.setProperty("smoothZoom", Boolean.toString(smoothZoom));
        props.setProperty("worldWaypoints", Boolean.toString(worldWaypoints));
        props.setProperty("deathWaypoints", Boolean.toString(deathWaypoints));
        props.setProperty("radar",Boolean.toString(radar));
        props.setProperty("playerRadar",Boolean.toString(playerRadar));
        props.setProperty("beams",Boolean.toString(beams));
        props.setProperty("volumetricFog",Boolean.toString(volumetricFog));
        props.setProperty("highResolution",Boolean.toString(highResolution));
        props.setProperty("workers", Integer.toString(workers));
        props.setProperty("stride", Integer.toString(stride));
        props.setProperty("captureMs", Integer.toString(captureMs));
        props.setProperty("captures", Integer.toString(captures));
        props.setProperty("radius", Integer.toString(radius));
        props.setProperty("resident", Integer.toString(resident));
        props.setProperty("history", Integer.toString(history));
        props.setProperty("uploads", Integer.toString(uploads));
        props.setProperty("progressive", Boolean.toString(progressive));
        props.setProperty("fast", Boolean.toString(fast));
        props.setProperty("gpuPreview", Boolean.toString(gpuPreview));
        props.setProperty("gpuRefine", Boolean.toString(gpuRefine));
        props.setProperty("gpuFour", Boolean.toString(gpuFour));
        Path parent = Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(parent, "diorama-minimap-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temp)) {
                props.store(output, "Diorama minimap display preferences");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
