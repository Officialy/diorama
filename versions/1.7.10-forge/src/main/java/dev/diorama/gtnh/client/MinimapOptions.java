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
    private int size = DEFAULT_SIZE;

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
        String value = props.getProperty("size", "");
        size = "64".equals(value) ? 64 : "128".equals(value) ? 128 : DEFAULT_SIZE;
    }

    void save() throws IOException {
        Properties props = new Properties();
        props.setProperty("visible", Boolean.toString(visible));
        props.setProperty("coordinates", Boolean.toString(coordinates));
        props.setProperty("size", Integer.toString(size));
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
