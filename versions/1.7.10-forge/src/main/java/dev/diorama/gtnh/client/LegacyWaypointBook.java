package dev.diorama.gtnh.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

    record Point(String name, int x, int y, int z, boolean enabled) {

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
