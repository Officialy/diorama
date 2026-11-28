package dev.diorama.gtnh.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import dev.diorama.gtnh.client.LegacyWaypointBook.Point;

/** Detached, deterministic coverage of limits, persistence and the integrated save-session fence. */
public final class LegacyWaypointBookProbe {

    private static final long FIRST = 174_928_750_726L;
    private static final long SECOND = FIRST + 1;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("diorama-waypoint-probe-");
        Path save = root.resolve("world");
        Path lock = save.resolve("session.lock");
        Path file = save.resolve("waypoints.dwp");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            LegacyWaypointBook book = new LegacyWaypointBook();
            Point home = new Point("家 🗺", -29_999_984, 0, 29_999_983, true);
            Point cave = new Point("Mine", -12, 255, -500, false);
            Point death = new Point("Death @ 12,64,-9", 12, 64, -9, true);
            require(death.death() && death.markerColor() == 0xFF6262, "death marker classification");
            require(!home.death() && home.markerColor() == 0xFFC53D, "normal marker classification");
            expect(IOException.class, () -> book.save(file, FIRST));
            require(!Files.exists(save), "missing save was not created");
            Files.createDirectory(save);
            writeLock(lock, FIRST);
            require(
                LegacyWaypointBook.load(file, FIRST)
                    .points()
                    .isEmpty(),
                "missing book is empty");
            book.save(file, FIRST);
            require(!Files.exists(file), "empty unchanged book does not create a file");
            book.add(home);
            List<Point> first = book.points();
            expect(UnsupportedOperationException.class, () -> first.add(cave));
            book.add(cave);
            require(first.equals(List.of(home)), "returned snapshot stays immutable after edits");
            require(!Files.exists(file), "memory edits do not write themselves");
            book.save(file, FIRST);
            byte[] initial = Files.readAllBytes(file);
            digest.update(initial);
            LegacyWaypointBook reopened = LegacyWaypointBook.load(file, FIRST);
            require(
                reopened.points()
                    .equals(List.of(home, cave)),
                "roundtrip Unicode, coordinates, flag and order");
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_234_560_000L));
            FileTime before = Files.getLastModifiedTime(file);
            reopened.save(file, FIRST);
            require(
                Files.getLastModifiedTime(file)
                    .equals(before),
                "identical save does not replace file");

            Point renamed = new Point("Camp", 17, 64, -17, false);
            reopened.replace(0, renamed);
            reopened.remove(1);
            reopened.save(file, FIRST);
            require(
                LegacyWaypointBook.load(file, FIRST)
                    .points()
                    .equals(List.of(renamed)),
                "replace and remove persist");
            reopened.remove(0);
            reopened.save(file, FIRST);
            require(
                LegacyWaypointBook.load(file, FIRST)
                    .points()
                    .isEmpty(),
                "deleting the last point persists");
            digest.update(Files.readAllBytes(file));

            writeLock(lock, SECOND);
            expect(IOException.class, () -> LegacyWaypointBook.load(file, FIRST));
            expect(IOException.class, () -> book.save(file, FIRST));
            require(
                LegacyWaypointBook.load(file, SECOND)
                    .points()
                    .isEmpty(),
                "new lock can reopen the same book");
            Files.delete(file);
            Files.delete(lock);
            Files.delete(save);
            Files.createDirectory(save);
            writeLock(lock, SECOND);
            expect(IOException.class, () -> book.save(file, FIRST));
            require(!Files.exists(file), "old session cannot write into a replacement save");
            byte[] malformedLock = Arrays.copyOf(Files.readAllBytes(lock), 9);
            Files.write(lock, malformedLock);
            expect(IOException.class, () -> LegacyWaypointBook.load(file, SECOND));
            writeLock(lock, SECOND);

            rejectsCorruption(file, SECOND, initial, book);
            Files.delete(file);
            digest.update(checkBounds(file));
            imports();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(lock);
            Files.deleteIfExists(save);
            Files.deleteIfExists(root);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out
            .println("PASS waypoint book: " + checks + " checks; bounds, roundtrip, corruption and session fence");
    }

    private static void imports() throws IOException {
        List<Point> xaero = LegacyWaypointBook.xaero(new java.io.StringReader(
            "waypoint:Mine:M:-12:64:42:1:false:0\nwaypoint:Bad:B:0:~:0:1:false:0\n"
                + "waypoint:Home:H:3:70:-5:2:true:0\n"));
        require(xaero.equals(List.of(new Point("Mine",-12,64,42,true), new Point("Home",3,70,-5,false))),
            "Xaero import preserves coordinates and visibility, skips unknown height");
        String json = "{\"name\":\"Portal\",\"x\":-88,\"y\":64,\"z\":40,\"enable\":true,\"dimensions\":[-1,0]}";
        require(LegacyWaypointBook.journeyMap(new java.io.StringReader(json),-1)
            .equals(List.of(new Point("Portal",-11,64,5,true))), "JourneyMap Nether coordinate scaling");
        require(LegacyWaypointBook.journeyMap(new java.io.StringReader(json),7).isEmpty(),
            "JourneyMap excludes unrelated dimensions");
        LegacyWaypointBook book = new LegacyWaypointBook();
        book.add(new Point("Mine",-12,64,42,false));
        require(book.merge(xaero) == 1 && book.merge(xaero) == 0, "repeat import is idempotent");
        require(!book.points().get(0).enabled(), "import preserves edited visibility");
    }

    private static void rejectsCorruption(Path file, long lock, byte[] initial, LegacyWaypointBook book)
        throws IOException {
        byte[] badHeader = initial.clone();
        badHeader[3]++;
        byte[] badCount = initial.clone();
        badCount[5] = (byte) 129;
        byte[] badNameLength = initial.clone();
        badNameLength[7] = (byte) 145;
        byte[] brokenUtf8 = initial.clone();
        brokenUtf8[8] = (byte) 0xFF;
        byte[] badEnabled = initial.clone();
        badEnabled[initial.length - 1] = 2;
        for (byte[] damaged : List.of(
            badHeader,
            badCount,
            badNameLength,
            brokenUtf8,
            badEnabled,
            Arrays.copyOf(initial, initial.length - 1),
            Arrays.copyOf(initial, initial.length + 1),
            new byte[65_536])) {
            Files.write(file, damaged);
            expect(IOException.class, () -> LegacyWaypointBook.load(file, lock));
            expect(IOException.class, () -> book.save(file, lock));
            require(Arrays.equals(damaged, Files.readAllBytes(file)), "unsupported bytes remain untouched");
        }
    }

    private static byte[] checkBounds(Path file) throws IOException {
        expect(IllegalArgumentException.class, () -> new Point(null, 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("", 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point(" 　", 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("a\nb", 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("§b", 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("\uD800", 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("a".repeat(49), 0, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("a", 29_999_984, 0, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("a", 0, 0, -29_999_985, true));
        expect(IllegalArgumentException.class, () -> new Point("a", 0, -1, 0, true));
        expect(IllegalArgumentException.class, () -> new Point("a", 0, 256, 0, true));
        LegacyWaypointBook full = new LegacyWaypointBook();
        Point widest = new Point("界".repeat(48), 17, 21, -26, true);
        for (int i = 0; i < LegacyWaypointBook.MAX_POINTS; i++) full.add(widest);
        expect(IllegalStateException.class, () -> full.add(widest));
        expect(IndexOutOfBoundsException.class, () -> full.remove(-1));
        expect(IndexOutOfBoundsException.class, () -> full.replace(128, widest));
        full.save(file, SECOND);
        require(
            LegacyWaypointBook.load(file, SECOND)
                .points()
                .equals(full.points()),
            "maximum-size book roundtrips");
        return Files.readAllBytes(file);
    }

    private static void writeLock(Path lock, long value) throws IOException {
        Files.write(
            lock,
            ByteBuffer.allocate(Long.BYTES)
                .putLong(value)
                .array());
    }

    private static void require(boolean success, String label) {
        if (!success) throw new AssertionError(label);
        checks++;
    }

    private static void expect(Class<? extends Exception> expected, CheckedAction action) {
        try {
            action.run();
        } catch (Exception error) {
            require(expected.isInstance(error), "Expected " + expected.getSimpleName() + ", got " + error);
            return;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    @FunctionalInterface
    private interface CheckedAction {

        void run() throws IOException;
    }
}
