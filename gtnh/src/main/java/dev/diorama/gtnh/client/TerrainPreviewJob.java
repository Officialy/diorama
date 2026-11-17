package dev.diorama.gtnh.client;

import java.util.Objects;
import java.util.function.Supplier;

import dev.diorama.bake.TileImage;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Pure one-shot worker. Closing discards output; it never pretends an uninterruptible bake stopped. */
public final class TerrainPreviewJob implements AutoCloseable {

    private static Thread busy;
    private Supplier<TileImage> task;
    private final Thread thread;
    private TileImage image;
    private String failure;
    private boolean done;
    private boolean discarded;

    private TerrainPreviewJob(Supplier<TileImage> task) {
        this.task = task;
        this.thread = new Thread(this::run, "Diorama-terrain-preview");
        this.thread.setDaemon(true);
    }

    public static TerrainPreviewJob start(TerrainPreview.Work work) {
        Objects.requireNonNull(work, "work");
        return startTask(work::bake);
    }

    // Package-private injection only for pure latch-based probes, never native callbacks.
    static synchronized TerrainPreviewJob startTask(Supplier<TileImage> task) {
        Objects.requireNonNull(task, "task");
        if (isBusy()) return null;
        TerrainPreviewJob job = new TerrainPreviewJob(task);
        busy = job.thread;
        try {
            job.thread.start();
        } catch (RuntimeException | Error error) {
            job.task = null;
            busy = null;
            throw error;
        }
        return job;
    }

    public static synchronized boolean isBusy() {
        // Only a truly terminated thread frees the slot, not done/discard/interrupt.
        if (busy != null && !busy.isAlive()) busy = null;
        return busy != null;
    }

    private void run() {
        Supplier<TileImage> input;
        synchronized (this) {
            input = this.task;
            this.task = null;
        }
        TileImage output = null;
        String error = null;
        try {
            if (input != null) output = Objects.requireNonNull(input.get(), "bake result");
        } catch (Throwable failed) {
            error = failed.getClass()
                .getSimpleName();
        } finally {
            // The terminating daemon does not retain snapshots, Looks or exceptions.
            input = null;
            synchronized (this) {
                if (!this.discarded) {
                    this.image = output;
                    this.failure = error;
                }
                this.task = null;
                this.done = true;
            }
        }
    }

    public synchronized boolean done() {
        return this.done;
    }

    public synchronized String failure() {
        return this.failure;
    }

    public synchronized TileImage takeImage() {
        TileImage result = this.image;
        this.image = null;
        return result;
    }

    @Override
    public synchronized void close() {
        this.discarded = true;
        this.task = null;
        this.image = null;
        this.failure = null;
    }

    // Probe synchronization; no sleeps, no callback and no new scheduling surface.
    void awaitTermination() throws InterruptedException {
        this.thread.join(10000);
        if (this.thread.isAlive()) throw new IllegalStateException("Probe worker did not terminate");
    }

    synchronized boolean retainsInput() {
        return this.task != null;
    }
}
