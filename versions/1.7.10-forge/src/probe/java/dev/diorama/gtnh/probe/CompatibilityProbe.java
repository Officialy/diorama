package dev.diorama.gtnh.probe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import dev.diorama.bake.Mipmaps;

/** Offline diagnostic entry point. Never called by the mod lifecycle. */
public final class CompatibilityProbe {

    // Same signature-polymorphic array access used by Looks, not a field-only substitute.
    private static final VarHandle VALUE = MethodHandles.arrayElementVarHandle(byte[].class);

    private record Sample(int number, String label) {}

    private record ArraySample(int[] values) {}

    public static void main(String[] args) throws InterruptedException, NoSuchAlgorithmException {
        java.util.ArrayList<Throwable> failures = new java.util.ArrayList<>();
        runCase("records", CompatibilityProbe::records, failures);
        runCase("List.of", CompatibilityProbe::collections, failures);
        runCase("Stream.toList", CompatibilityProbe::streamLists, failures);
        runCase("publication", CompatibilityProbe::publication, failures);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        runCase("Mipmaps", () -> mipmaps(digest), failures);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        if (!failures.isEmpty()) {
            AssertionError summary = new AssertionError("Compatibility probe failed " + failures.size() + " case(s)");
            for (Throwable failure : failures) summary.addSuppressed(failure);
            throw summary;
        }
        System.out.println(
            "PASS record collections VarHandle-release/acquire Mipmaps; Java=" + System.getProperty("java.version"));
    }

    @FunctionalInterface
    private interface ProbeCase {

        void run() throws Exception;
    }

    private static void runCase(String name, ProbeCase test, java.util.List<Throwable> failures) {
        try {
            test.run();
            System.out.println("CASE-PASS " + name);
        } catch (Exception | AssertionError problem) {
            failures.add(problem);
            System.out.println("CASE-FAIL " + name + ": " + problem);
        }
    }

    private static void records() {
        Sample sample = new Sample(7, "record");
        Sample equal = new Sample(7, "record");
        require(sample.equals(equal) && equal.equals(sample), "record equality");
        require(sample.hashCode() == equal.hashCode(), "record hashCode");
        require(
            !sample.equals(new Sample(8, "record")) && !sample.equals(null) && !sample.equals("record"),
            "record unequal values/types");
        require(
            sample.number() == 7 && sample.label()
                .equals("record"),
            "record accessors");
        String recordText = sample.toString();
        String nativeText = "Sample[number=7, label=record]";
        String knownDowngradedText = "CompatibilityProbe$Sample[number=7, label=record]";
        require(
            recordText.equals(nativeText) || recordText.equals(knownDowngradedText),
            "unexpected record toString: " + recordText);
        if (!recordText.equals(nativeText)) {
            System.out.println("KNOWN-DEVIATION nested-record-toString=" + recordText);
        }
        if (Boolean.getBoolean("diorama.probe.strictRecordText")) {
            require(recordText.equals(nativeText), "strict record toString: " + recordText);
        }
        require(new Sample(0, null).equals(new Sample(0, null)), "record nullable component");
        int[] array = { 1, 2 };
        ArraySample shallow = new ArraySample(array);
        require(shallow.values() == array && shallow.equals(new ArraySample(array)), "record array identity");
        require(!shallow.equals(new ArraySample(new int[] { 1, 2 })), "record arrays are not deep-equal");
    }

    private static void collections() {
        require(
            List.of()
                .isEmpty(),
            "List.of empty");
        require(
            List.of(7)
                .equals(Arrays.asList(7)),
            "List.of singleton");
        List<Integer> many = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        require(many.equals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)), "List.of varargs order");
        rejects(UnsupportedOperationException.class, () -> many.set(0, 99), "List.of immutable");
        rejects(
            UnsupportedOperationException.class,
            () -> List.of()
                .add(1),
            "empty List.of immutable");
        rejects(NullPointerException.class, () -> List.of((String) null), "List.of singleton null");
        rejects(NullPointerException.class, () -> List.of("a", null, "b"), "List.of null element");
    }

    private static void streamLists() {
        List<Integer> numbers = List.of(1, 2, 3)
            .stream()
            .map(n -> n * 2)
            .toList();
        require(numbers.equals(Arrays.asList(2, 4, 6)), "Stream.toList encounter order");
        rejects(UnsupportedOperationException.class, () -> numbers.add(8), "Stream.toList immutable add");
        rejects(UnsupportedOperationException.class, () -> numbers.set(0, 8), "Stream.toList immutable set");
        List<String> nullable = Stream.of("first", null, "last")
            .toList();
        require(nullable.equals(Arrays.asList("first", null, "last")), "Stream.toList permits null");
        List<String> empty = Stream.<String>empty()
            .toList();
        require(empty.isEmpty(), "Stream.toList empty");
        rejects(UnsupportedOperationException.class, () -> empty.add("x"), "empty Stream.toList immutable");
    }

    private static void publication() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (int round = 0; round < 16; round++) {
            // Fresh tables avoid a previous ready value or a reset race satisfying the acquire.
            byte[] kinds = new byte[64];
            int[] colours = new int[64];
            int[] shapes = new int[64];
            int seed = round + 1;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Runnable read = () -> {
                try {
                    for (int id = 0; id < kinds.length; id++) {
                        while ((byte) VALUE.getAcquire(kinds, id) != 1) {
                            require(System.nanoTime() - deadline < 0, "VarHandle publication timeout");
                            Thread.yield();
                        }
                        // No join, latch, or other reader synchronization before these payload reads.
                        require(
                            colours[id] == seed * 101 + id && shapes[id] == ~(seed * 101 + id),
                            "release/acquire payload visibility");
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            };
            Thread first = new Thread(read, "diorama-probe-reader-1");
            Thread second = new Thread(read, "diorama-probe-reader-2");
            Thread writer = new Thread(() -> {
                for (int id = 0; id < kinds.length; id++) {
                    colours[id] = seed * 101 + id;
                    shapes[id] = ~colours[id];
                    VALUE.setRelease(kinds, id, (byte) 1);
                }
            }, "diorama-probe-writer");
            // A broken implementation cannot keep the diagnostic process alive after timeout.
            first.setDaemon(true);
            second.setDaemon(true);
            writer.setDaemon(true);
            first.start();
            second.start();
            writer.start();
            await(first, deadline);
            await(second, deadline);
            await(writer, deadline);
            if (failure.get() != null) throw new AssertionError("VarHandle reader failed", failure.get());
        }
    }

    private static void await(Thread thread, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        require(remaining > 0, "VarHandle probe deadline");
        thread.join(Math.max(1, remaining / 1_000_000L));
        require(!thread.isAlive(), "VarHandle thread termination timeout");
    }

    private static void mipmaps(MessageDigest digest) {
        require(Mipmaps.average(0, 0, 0, 0) == 0, "unknown pixels");
        require(Mipmaps.average(0x000000FF, 0x0000FF00, 0x00FF0000, 0) == 0, "transparent RGB discarded");
        for (int primary : new int[] { 0xFF0000FF, 0xFF00FF00, 0xFFFF0000 }) {
            require(Mipmaps.average(primary, primary, primary, primary) == primary, "ABGR primary order");
        }
        require(
            Mipmaps.average(0xFF0000FF, 0x0000FF00, 0x00FF0000, 0x00FFFFFF) == 0x3F0000FF,
            "transparent edges retain red and truncate alpha");
        require(Mipmaps.average(0xFF0000FF, 0x80FF0000, 0, 0) == 0x5F5500A9, "unequal alpha weights");
        require(
            Mipmaps.average(0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFFFFFFFF) == 0xFF7F7F7F,
            "opaque channel truncation");
        require(Mipmaps.LEVELS == 6, "six mip levels");
        for (int size : new int[] { 32, 512 }) {
            for (boolean uniform : new boolean[] { true, false }) {
                int[] base = new int[size * size];
                for (int i = 0; i < base.length; i++) {
                    base[i] = uniform ? 0xFF332211 : i * 0x9E3779B9 + 0x12345678;
                }
                int[] original = base.clone();
                int[][] mips = Mipmaps.build(base, size);
                require(mips.length == Mipmaps.LEVELS && mips[0] == base, "mip count/base identity");
                require(Arrays.equals(base, original), "base not mutated");
                // Explicit big-endian ints make the digest independent of JVM/native byte order.
                addInt(digest, size);
                addInt(digest, uniform ? 1 : 0);
                for (int level = 0; level < mips.length; level++) {
                    int edge = size >> level;
                    require(mips[level].length == edge * edge, "mip dimensions");
                    addInt(digest, mips[level].length);
                    for (int pixel : mips[level]) {
                        if (uniform) require(pixel == 0xFF332211, "constant colour mips");
                        addInt(digest, pixel);
                    }
                }
            }
        }
    }

    private static void addInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable operation, String message) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            if (type.isInstance(exception)) return;
            throw new AssertionError(message, exception);
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
