package dev.diorama.gtnh.client;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewTileStore;

/** Detached, latch-controlled fixtures; no game classes or scheduling sleeps. */
public final class SharedTileIoProbe {

    private static final TileKey DETAIL = new TileKey(TileKey.SURFACE, -1, -2);
    private static final TileKey OVERVIEW = DETAIL.parent();
    private static final int OLD = 0xFF112233;
    private static final int NEW = 0x7F445566;
    private static final int OTHER = 0xFF778899;
    private static MessageDigest digest;

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        Path temp = Files.createTempDirectory("diorama-shared-io-");
        try {
            sessionReset(temp);
            coalesceAndBound(temp);
            lockChanged(temp);
        } finally {
            Path[] paths;
            try (Stream<Path> tree = Files.walk(temp)) {
                paths = tree.sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new);
            }
            for (Path path : paths) Files.delete(path);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println("PASS shared tile I/O: main-thread drain, session reset, bounded coalescing, OMT, lock");
    }

    private static void sessionReset(Path temp) throws Exception {
        Path save = Files.createDirectory(temp.resolve("save-old"));
        sessionLock(save, 1L);
        PreviewTileStore old = PreviewTileStore.forSave(save);
        PreviewTileStore current = new PreviewTileStore(temp.resolve("save-new"));
        old.write(DETAIL, pixels(0, OLD));
        current.write(DETAIL, pixels(0, NEW));
        Gate gate = new Gate();
        SharedTileIo io = new SharedTileIo(gate::beforeIo);
        List<SharedTileIo.Read> results = new ArrayList<>();
        try {
            io.reset(old);
            require(io.read(DETAIL), "old read accepted");
            gate.awaitStart();
            require(io.read(OVERVIEW), "queued old read accepted");
            require(io.write(OVERVIEW, pixels(0, OTHER)), "old write accepted before close");
            io.drain(results::add);
            require(results.isEmpty(), "blocked worker does not deliver inline");
            io.reset(null);
            require(!io.read(DETAIL) && !io.write(DETAIL, pixels(0, OTHER)), "closed session admits nothing");
            io.reset(current);
            require(io.read(DETAIL) && io.read(DETAIL), "same key may be requested in the new session; deduplicated");
        } finally {
            gate.release.countDown();
            io.awaitIdle();
        }
        Thread main = Thread.currentThread();
        io.drain(result -> {
            require(Thread.currentThread() == main, "consumer runs on the drainer");
            results.add(result);
        });
        require(gate.calls == 3, "one daemon ran old in-flight read, surviving write, and new read only");
        require(results.size() == 1, "neither queued nor in-flight old read is reported");
        SharedTileIo.Read result = results.get(0);
        require(
            result.key()
                .equals(DETAIL) && result.failure() == null
                && Arrays.equals(result.pixels(), pixels(0, NEW)),
            "same key returns only current session data");
        require(Arrays.equals(old.read(OVERVIEW), pixels(0, OTHER)), "accepted write survived close on original store");
        require(current.read(OVERVIEW) == null, "accepted write did not leak into the new store");
    }

    private static void coalesceAndBound(Path temp) throws Exception {
        Path root = temp.resolve("bounded");
        PreviewTileStore store = new PreviewTileStore(root);
        store.write(OVERVIEW, pixels(0, OLD));
        Path omt = root.resolve("lod1")
            .resolve(OVERVIEW.tileU() + "_" + OVERVIEW.tileV() + ".omt");
        Gate gate = new Gate();
        SharedTileIo io = new SharedTileIo(gate::beforeIo);
        io.reset(store);
        int[] unknown = pixels(0, 0);
        try {
            require(io.write(key(0), unknown), "blocking write accepted");
            gate.awaitStart();
            int[] first = pixels(1, OLD);
            first[2] = OLD;
            require(io.write(OVERVIEW, first), "first patch accepted");
            first[1] = 0; // The queue owns a detached copy.
            for (int i = 2; i < SharedTileIo.WRITE_LIMIT; i++) require(io.write(key(i), unknown), "write slot " + i);
            require(!io.write(key(99), unknown), "write cap includes the in-flight write");
            int[] newest = pixels(2, NEW);
            newest[3] = OTHER;
            require(io.write(OVERVIEW, newest), "same-key patch coalesces at capacity");
            newest[2] = 0;
            for (int i = 0; i < SharedTileIo.READ_LIMIT; i++) require(io.read(key(i)), "read slot " + i);
            require(!io.read(key(99)), "reads are bounded independently of writes");
        } finally {
            gate.release.countDown();
            io.awaitIdle();
        }
        require(
            gate.calls == SharedTileIo.READ_LIMIT + SharedTileIo.WRITE_LIMIT,
            "coalesced patches use one worker job");
        require(!io.read(key(99)), "undrained completions still occupy read slots");
        List<SharedTileIo.Read> results = new ArrayList<>();
        io.drain(results::add);
        require(results.size() == SharedTileIo.READ_LIMIT, "each accepted read completed once");
        for (int i = 0; i < results.size(); i++) {
            SharedTileIo.Read result = results.get(i);
            require(
                result.key()
                    .equals(key(i)) && result.pixels() == null
                    && result.failure() == null,
                "ordered miss " + i);
        }
        int[] merged = pixels(0, OLD);
        merged[1] = OLD;
        merged[2] = NEW;
        merged[3] = OTHER;
        require(
            Files.isRegularFile(omt) && Arrays.equals(store.read(OVERVIEW), merged),
            "canonical OMT merges disk and patches");

        require(io.read(OVERVIEW), "slots freed by drain");
        io.awaitIdle();
        io.reset(store); // Even already-completed reads belong to the old generation.
        results.clear();
        io.drain(results::add);
        require(results.isEmpty(), "reset discards unread completions even for the same store");
        byte[] corrupt = { 1, 2, 3 };
        Files.write(omt, corrupt);
        require(io.write(OVERVIEW, merged) && io.read(OVERVIEW), "requests accepted for corrupt tile");
        io.awaitIdle();
        io.drain(results::add);
        require(
            results.size() == 1 && results.get(0)
                .pixels() == null
                && results.get(0)
                    .failure() instanceof IOException,
            "corruption is distinguishable from a miss");
        require(Arrays.equals(corrupt, Files.readAllBytes(omt)), "corrupt OMT is never replaced");
    }

    private static void lockChanged(Path temp) throws Exception {
        Path save = Files.createDirectory(temp.resolve("save-reopened"));
        sessionLock(save, 20L);
        PreviewTileStore store = PreviewTileStore.forSave(save);
        store.write(DETAIL, pixels(0, OLD));
        Gate gate = new Gate();
        SharedTileIo io = new SharedTileIo(gate::beforeIo);
        io.reset(store);
        try {
            require(io.write(DETAIL, pixels(0, NEW)), "write accepted under original lock");
            gate.awaitStart();
            io.reset(null);
            sessionLock(save, 21L);
        } finally {
            gate.release.countDown();
            io.awaitIdle();
        }
        PreviewTileStore reopened = PreviewTileStore.forSave(save);
        require(Arrays.equals(reopened.read(DETAIL), pixels(0, OLD)), "stale write respects lock");
    }

    private static TileKey key(int n) {
        return new TileKey(TileKey.SURFACE, n, 8);
    }

    private static int[] pixels(int index, int value) {
        int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
        pixels[index] = value;
        return pixels;
    }

    private static void sessionLock(Path save, long id) throws IOException {
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(save.resolve("session.lock")))) {
            output.writeLong(id);
        }
    }

    private static void require(boolean success, String reason) {
        if (!success) throw new AssertionError(reason);
        if (!Thread.currentThread()
            .isDaemon()) digest.update(reason.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Gate {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Thread worker;
        private int calls;

        private void beforeIo() {
            if (this.calls++ == 0) {
                this.worker = Thread.currentThread();
                this.entered.countDown();
                try {
                    require(this.release.await(10, TimeUnit.SECONDS), "worker gate released");
                } catch (InterruptedException interrupted) {
                    throw new AssertionError(interrupted);
                }
            }
            require(Thread.currentThread() == this.worker && this.worker.isDaemon(), "single daemon handles all I/O");
        }

        private void awaitStart() throws InterruptedException {
            require(this.entered.await(10, TimeUnit.SECONDS), "worker entered gate");
        }
    }
}
