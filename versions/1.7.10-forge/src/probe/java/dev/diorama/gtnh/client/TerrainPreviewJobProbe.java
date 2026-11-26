package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.diorama.Projection;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewTileStore;

/** Pure worker lifecycle probe: latch synchronization, never Minecraft callbacks or timed sleeps. */
public final class TerrainPreviewJobProbe {

    private static int assertions;
    private static MessageDigest hash;

    public static void main(String[] args) throws Exception {
        hash = MessageDigest.getInstance("SHA-256");
        require(!TerrainPreviewJob.isBusy(), "initial worker slot empty");
        TerrainPreviewFrame image = new TerrainPreviewFrame(new int[] { 0xFF030201 }, null, null, false);
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
            require(first.takeFrame() == null, "no premature output");
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
        require(first.takeFrame() == null, "closed output discarded");
        require(first.failure() == null && !first.retainsInput(), "discarded job retains no payload or exception");
        require(!TerrainPreviewJob.isBusy(), "actual thread termination releases busy");

        TerrainPreviewJob success = TerrainPreviewJob.startTask(() -> image);
        require(success != null, "new request after termination accepted");
        success.awaitTermination();
        require(success.done() && !success.retainsInput(), "completed success releases task");
        require(success.failure() == null, "success no error");
        require(success.takeFrame() == image, "published exact image");
        require(success.takeFrame() == null, "result consumed once and field cleared");
        success.close();
        success.close();
        require(success.takeFrame() == null, "close idempotent");

        TerrainPreviewJob failure = TerrainPreviewJob
            .startTask(() -> { throw new IllegalStateException("pure fixture"); });
        require(failure != null, "failure request accepted");
        failure.awaitTermination();
        require(failure.done() && !failure.retainsInput(), "failed task released");
        require(
            "IllegalStateException".equals(failure.failure()),
            "failure is bounded class name, not retained Throwable");
        require(failure.takeFrame() == null, "failed task has no result");
        require(!TerrainPreviewJob.isBusy(), "failure frees slot after termination");
        failure.close();
        require(failure.failure() == null, "close clears failure");

        TerrainPreviewJob nullResult = TerrainPreviewJob.startTask(() -> null);
        nullResult.awaitTermination();
        require("NullPointerException".equals(nullResult.failure()), "null result explicitly fails");
        nullResult.close();
        expectNull(() -> TerrainPreviewJob.startTask(null));
        expectNull(() -> TerrainPreviewJob.start(null, null, null, null));
        workerRoundTrip();
        parallelSlots(image);
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

    private static void parallelSlots(TerrainPreviewFrame image) throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        TerrainPreviewJob first = TerrainPreviewJob.startParallel(() -> {
            entered.countDown();
            await(release);
            return image;
        }, 2);
        TerrainPreviewJob second = TerrainPreviewJob.startParallel(() -> {
            entered.countDown();
            await(release);
            return image;
        }, 2);
        try {
            require(first != null && second != null, "two independent detail workers");
            await(entered);
            require(TerrainPreviewJob.startParallel(() -> image, 2) == null, "parallel capacity bounded");
            first.close();
            require(TerrainPreviewJob.startParallel(() -> image, 2) == null, "discarded running job occupies slot");
            require(TerrainPreviewJob.startTask(() -> image) == null, "legacy worker cannot overlap detail pool");
        } finally {
            release.countDown();
            if (first != null) first.awaitTermination();
            if (second != null) second.awaitTermination();
        }
        require(first.takeFrame() == null && second.takeFrame() == image, "parallel results isolated");
        require(!TerrainPreviewJob.isBusy(), "parallel slots released after termination");
        second.close();
    }

    private static void workerRoundTrip() throws Exception {
        Path root = Files.createTempDirectory("diorama-worker-probe");
        TileKey key = new TileKey(TileKey.SURFACE, -1, 0);
        Path detailFile = root.resolve("-1_0.omt"), overviewFolder = root.resolve("lod1");
        Path overviewFile = overviewFolder.resolve("-1_0.omt");
        try {
            PreviewTileStore store = new PreviewTileStore(root);
            int[] raster = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
            raster[0] = 0xFF453201;
            TileImage image = TileImage.full(key, new int[][] { raster }, new byte[0]);
            TerrainPreviewJob bake = TerrainPreviewJob
                .startTask(() -> TerrainPreviewFrame.assemble(key, image, store, null));
            bake.awaitTermination();
            TerrainPreviewFrame frame = bake.takeFrame();
            require(
                frame != null && frame.needsSave() && !Files.exists(detailFile) && !Files.exists(overviewFile),
                "speculative bake stages but does not write");
            bake.close();
            TerrainPreviewJob save = TerrainPreviewJob.startSave(store, key, frame);
            require(save != null, "done bake frees slot for accepted save");
            save.close(); // Even closing before worker entry must not cancel accepted persistence.
            save.awaitTermination();
            require(
                Files.exists(detailFile) && Files.exists(overviewFile),
                "accepted L0 and L1 survive closing the handle");
            TerrainPreviewJob restore = TerrainPreviewJob.start(key, null, store, null);
            restore.awaitTermination();
            TerrainPreviewFrame restored = restore.takeFrame();
            require(
                restored != null && !restored.needsSave() && restored.storageFailure() == null,
                "restoring without a snapshot succeeds without staging a write");
            require(
                restored.detail()[0] == raster[0] && restored.overview()[Projection.TILE_PIXELS / 2] != 0,
                "restores both tiers with negative-coordinate child placement");
            restore.close();
        } finally {
            Files.deleteIfExists(overviewFile);
            Files.deleteIfExists(overviewFolder);
            Files.deleteIfExists(detailFile);
            Files.delete(root);
        }
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
