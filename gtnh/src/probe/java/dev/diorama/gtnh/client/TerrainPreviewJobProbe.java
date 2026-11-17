package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;

/** Pure worker lifecycle probe: latch synchronization, never Minecraft callbacks or timed sleeps. */
public final class TerrainPreviewJobProbe {

    private static int assertions;
    private static MessageDigest hash;

    public static void main(String[] args) throws Exception {
        hash = MessageDigest.getInstance("SHA-256");
        require(!TerrainPreviewJob.isBusy(), "initial worker slot empty");
        TileImage image = TileImage
            .full(new TileKey(TileKey.SURFACE, 0, 0), new int[][] { { 0xFF030201 } }, new byte[1]);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean daemon = new AtomicBoolean();
        TerrainPreviewJob first = TerrainPreviewJob.startTask(() -> {
            daemon.set(
                Thread.currentThread()
                    .isDaemon());
            entered.countDown();
            await(release);
            return image;
        });
        try {
            require(first != null, "first request accepted");
            await(entered);
            require(daemon.get(), "worker daemon");
            require(TerrainPreviewJob.isBusy(), "running worker counted busy");
            require(!first.done(), "blocked worker not complete");
            require(first.takeImage() == null, "no premature output");
            first.close();
            require(!first.retainsInput(), "closed handle releases input field");
            require(TerrainPreviewJob.isBusy(), "close does not free running worker slot");
            require(!first.done(), "close does not pretend bake completed");
            for (int i = 0; i < 100; i++) require(
                TerrainPreviewJob.startTask(() -> image) == null,
                "repeat requests cannot spawn while discarded work runs");
        } finally {
            release.countDown();
            if (first != null) first.awaitTermination();
        }
        require(first.done(), "discarded work actually completed");
        require(first.takeImage() == null, "closed output discarded");
        require(first.failure() == null && !first.retainsInput(), "discarded job retains no payload or exception");
        require(!TerrainPreviewJob.isBusy(), "actual thread termination releases busy");

        TerrainPreviewJob success = TerrainPreviewJob.startTask(() -> image);
        require(success != null, "new request after termination accepted");
        success.awaitTermination();
        require(success.done() && !success.retainsInput(), "completed success releases task");
        require(success.failure() == null, "success no error");
        require(success.takeImage() == image, "published exact image");
        require(success.takeImage() == null, "result consumed once and field cleared");
        success.close();
        success.close();
        require(success.takeImage() == null, "close idempotent");

        TerrainPreviewJob failure = TerrainPreviewJob
            .startTask(() -> { throw new IllegalStateException("pure fixture"); });
        require(failure != null, "failure request accepted");
        failure.awaitTermination();
        require(failure.done() && !failure.retainsInput(), "failed task released");
        require(
            "IllegalStateException".equals(failure.failure()),
            "failure is bounded class name, not retained Throwable");
        require(failure.takeImage() == null, "failed task has no result");
        require(!TerrainPreviewJob.isBusy(), "failure frees slot after termination");
        failure.close();
        require(failure.failure() == null, "close clears failure");

        TerrainPreviewJob nullResult = TerrainPreviewJob.startTask(() -> null);
        nullResult.awaitTermination();
        require("NullPointerException".equals(nullResult.failure()), "null result explicitly fails");
        nullResult.close();
        expectNull(() -> TerrainPreviewJob.startTask(null));
        expectNull(() -> TerrainPreviewJob.start(null));
        require(!TerrainPreviewJob.isBusy(), "all workers terminated");
        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS terrain job: " + assertions
                + " assertions; one worker, busy after discard, daemon, release, error; native screen/GL UNTESTED");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Probe latch timeout");
        } catch (InterruptedException error) {
            Thread.currentThread()
                .interrupt();
            throw new AssertionError("Probe interrupted", error);
        }
    }

    private static void expectNull(Runnable action) {
        try {
            action.run();
        } catch (NullPointerException expected) {
            assertions++;
            return;
        }
        throw new AssertionError("Expected null rejection");
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
        hash.update(message.getBytes(StandardCharsets.UTF_8));
    }
}
