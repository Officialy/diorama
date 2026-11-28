package dev.diorama.gtnh.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bounded Overworld waypoints; the caller owns a book and serializes edits for its world session. */
final class LegacyWaypointBook {

    static final int MAX_POINTS = 128;
    static final int MAX_NAME = 48;
    private static final int MAX_NAME_BYTES = MAX_NAME * 3;
    private static final int COORD_LIMIT = 29_999_984;
    private static final int MAGIC = 0x44575031; // DWP1
    private static final int MAX_FILE_BYTES = 6 + MAX_POINTS * (15 + MAX_NAME_BYTES);
    private List<Point> points = List.of();

    LegacyWaypointBook() {}

    List<Point> points() {
        return points;
    }

    void add(Point point) {
        if (points.size() >= MAX_POINTS) throw new IllegalStateException("Waypoint capacity reached");
        List<Point> next = new ArrayList<>(points);
        next.add(Objects.requireNonNull(point));
        points = List.copyOf(next);
    }

    void replace(int index, Point point) {
        List<Point> next = new ArrayList<>(points);
        next.set(index, Objects.requireNonNull(point));
        points = List.copyOf(next);
    }

    void remove(int index) {
        List<Point> next = new ArrayList<>(points);
        next.remove(index);
        points = List.copyOf(next);
    }

    /** Merge without replacing an existing point or changing its visibility. */
    int merge(List<Point> imported) {
        Set<String> keys = new HashSet<>();
        for (Point point : points) keys.add(importKey(point));
        int added = 0;
        for (Point point : imported) {
            if (points.size() >= MAX_POINTS) break;
            if (keys.add(importKey(point))) {
                add(point);
                added++;
            }
        }
        return added;
    }

    private static String importKey(Point point) {
        return new StringBuilder(point.label().toLowerCase(Locale.ROOT)).append((char) 0).append(point.x())
            .append(',').append(point.y()).append(',').append(point.z()).toString();
    }

    /** Xaero text files describe a single dimension, explicitly selected by the user. */
    static List<Point> xaero(Reader reader) throws IOException {
        List<Point> points = new ArrayList<>();
        java.io.BufferedReader lines = new java.io.BufferedReader(reader);
        String line;
        while ((line = lines.readLine()) != null && points.size() < MAX_POINTS) {
            if (!line.startsWith("waypoint:") || line.length() > 4096) continue;
            String[] fields = line.split(":", -1);
            if (fields.length < 8 || !("true".equals(fields[7]) || "false".equals(fields[7]))) continue;
            try {
                points.add(new Point(fields[1], Integer.parseInt(fields[3]), Integer.parseInt(fields[4]),
                    Integer.parseInt(fields[5]), !Boolean.parseBoolean(fields[7])));
            } catch (IllegalArgumentException invalid) {
                // Unknown heights, invalid names and coordinates cannot become actual pins.
            }
        }
        return points;
    }

    /** Legacy JourneyMap 5.x serializes Nether x/z in Overworld units. Reject ambiguous dimensions. */
    static List<Point> journeyMap(Reader reader, int dimension) {
        JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
        if (!json.has("dimensions") || !json.get("dimensions").isJsonArray()) return List.of();
        boolean matches = false;
        for (JsonElement item : json.getAsJsonArray("dimensions")) {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isNumber()
                && exactInt(item) == dimension) matches = true;
        }
        if (!matches) return List.of();
        int x = exactInt(json.get("x")), y = exactInt(json.get("y")), z = exactInt(json.get("z"));
        if (dimension == -1) { x /= 8; z /= 8; }
        JsonElement enabled = json.get("enable");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
            return List.of();
        JsonElement name = json.get("name");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) return List.of();
        return List.of(new Point(name.getAsString(), x, y, z, enabled.getAsBoolean()));
    }

    private static int exactInt(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected an integer");
        return new java.math.BigDecimal(value.getAsString()).intValueExact();
    }

    /** Reads only explicitly staged regular files; total source bytes and file count are bounded. */
    static List<Point> readImports(Path directory, int dimension) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Import folder is not a regular directory");
        List<Point> points = new ArrayList<>();
        int files = 0;
        long bytes = 0;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (++files > 512) throw new IOException("Import folder exceeds 512 entries");
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json") && !name.endsWith(".txt")) continue;
                BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile() || attrs.size() > 262144) continue;
                bytes += attrs.size();
                if (bytes > 2097152) throw new IOException("Import exceeds 2 MiB");
                try (InputStream input = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] content = input.readNBytes(262145);
                    if (content.length > 262144) continue;
                    String decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
                    Reader reader = new java.io.StringReader(decoded);
                    List<Point> found = name.endsWith(".json") ? journeyMap(reader, dimension) : xaero(reader);
                    for (Point point : found) {
                        if (points.size() >= 4096) return points;
                        points.add(point);
                    }
                } catch (IOException | RuntimeException invalid) {
                    // A malformed source file cannot damage the existing waypoint book.
                }
            }
        }
        return points;
    }

    record Point(String name, int x, int y, int z, boolean enabled) {

        String icon() {
            if (death()) return "X";
            if (name.startsWith("[H] ")) return "H";
            if (name.startsWith("[M] ")) return "M";
            if (name.startsWith("[P] ")) return "P";
            if (name.startsWith("[!] ")) return "!";
            return "+";
        }

        String label() {
            return name.length() > 4 && name.charAt(0) == '[' && name.charAt(2) == ']'
                && name.charAt(3) == ' ' && "HMP!".indexOf(name.charAt(1)) >= 0 ? name.substring(4) : name;
        }

        boolean death() {
            return name.startsWith("Death @ ");
        }

        int markerColor() {
            if (death()) return 0xFF6262;
            return switch (icon()) {
                case "H" -> 0x8DE38D; case "M" -> 0xB8A6FF; case "P" -> 0x61B5FF;
                case "!" -> 0xFF8D49; default -> 0xFFC53D;
            };
        }

        Point {
            if (name == null || name.isEmpty() || name.length() > MAX_NAME)
                throw new IllegalArgumentException("Invalid waypoint name length");
            boolean nonBlank = false;
            for (int i = 0; i < name.length();) {
                int cp = name.codePointAt(i);
                if (Character.isISOControl(cp) || cp == 0x00A7 || (cp >= 0xD800 && cp <= 0xDFFF))
                    throw new IllegalArgumentException("Invalid waypoint name character");
                if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) nonBlank = true;
                i += Character.charCount(cp);
            }
            if (!nonBlank) throw new IllegalArgumentException("Blank waypoint name");
            if (x < -COORD_LIMIT || x >= COORD_LIMIT || z < -COORD_LIMIT || z >= COORD_LIMIT || y < 0 || y > 255)
                throw new IllegalArgumentException("Waypoint outside supported Overworld coordinates");
        }
    }

    /** A missing book is empty; invalid or stale data is an error. File is a sibling of session.lock. */
    static LegacyWaypointBook load(Path file, long lock) throws IOException {
        Path target = target(file);
        return readCurrent(target, lock);
    }

    /** Explicit edit save only. Never creates a world directory or replaces unsupported data. */
    void save(Path file, long lock) throws IOException {
        Path target = target(file);
        if (readCurrent(target, lock).points.equals(points)) return;
        Path temp = Files.createTempFile(target.getParent(), "diorama-waypoints-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeShort(points.size());
                for (Point point : points) {
                    byte[] name = point.name.getBytes(StandardCharsets.UTF_8);
                    out.writeShort(name.length);
                    out.write(name);
                    out.writeInt(point.x);
                    out.writeInt(point.y);
                    out.writeInt(point.z);
                    out.writeBoolean(point.enabled);
                }
            }
            // Recheck with the temp in the original save. If the directory was replaced after
            // this check the unique source path is gone, so it cannot publish into the new save.
            checkSession(target, lock);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static Path target(Path file) throws IOException {
        Path target = file.toAbsolutePath()
            .normalize();
        if (target.getParent() == null || target.getFileName()
            .toString()
            .equalsIgnoreCase("session.lock")) throw new IOException("Expected a waypoint book beside session.lock");
        return target;
    }

    private static void checkSession(Path file, long lock) throws IOException {
        Path sessionLock = file.resolveSibling("session.lock");
        BasicFileAttributes attributes = Files
            .readAttributes(sessionLock, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != Long.BYTES)
            throw new IOException("Invalid save session lock");
        try (
            DataInputStream input = new DataInputStream(Files.newInputStream(sessionLock, LinkOption.NOFOLLOW_LINKS))) {
            if (input.readLong() != lock || input.read() != -1) throw new IOException("Save session changed");
        }
    }

    private static LegacyWaypointBook readCurrent(Path file, long lock) throws IOException {
        checkSession(file, lock);
        LegacyWaypointBook result = read(file);
        checkSession(file, lock);
        return result;
    }

    private static LegacyWaypointBook read(Path file) throws IOException {
        LegacyWaypointBook book = new LegacyWaypointBook();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return book;
        }
        if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES)
            throw new IOException("Invalid waypoint book: " + file);
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported waypoint book: " + file);
            int count = in.readUnsignedShort();
            if (count > MAX_POINTS) throw new IOException("Too many entries in waypoint book: " + file);
            List<Point> points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int length = in.readUnsignedShort();
                if (length == 0 || length > MAX_NAME_BYTES) throw new IOException("Invalid waypoint name length");
                byte[] encoded = new byte[length];
                in.readFully(encoded);
                String name = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
                int x = in.readInt(), y = in.readInt(), z = in.readInt(), enabled = in.readUnsignedByte();
                if (enabled > 1) throw new IOException("Invalid waypoint enabled flag");
                points.add(new Point(name, x, y, z, enabled != 0));
            }
            if (in.read() != -1) throw new IOException("Unexpected waypoint book tail: " + file);
            book.points = List.copyOf(points);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid waypoint book: " + file, invalid);
        }
        return book;
    }
}
