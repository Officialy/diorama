package dev.diorama.gtnh.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewTileStore;
import dev.diorama.gtnh.capture.TerrainPreview;

/** Single detached worker slot for bake, restore and separately accepted writes. */
public final class TerrainPreviewJob implements AutoCloseable {

    private static Thread busy;
    private Supplier<TerrainPreviewFrame> task;
    private final boolean acceptedWrite;
    private final Thread thread;
    private TerrainPreviewFrame frame;
    private String failure;
    private boolean done;
    private boolean discarded;

    private TerrainPreviewJob(Supplier<TerrainPreviewFrame> task, boolean acceptedWrite) {
        this.task = task;
        this.acceptedWrite = acceptedWrite;
        this.thread = new Thread(this::run, "Diorama-terrain-preview");
        this.thread.setDaemon(true);
    }

    static @Nullable TerrainPreviewJob start(TileKey key, TerrainPreview.@Nullable Work work,
        @Nullable PreviewTileStore store, @Nullable TerrainPreviewFrame previous) {
        Objects.requireNonNull(key, "key");
        if (work != null && !key.equals(work.key())) throw new IllegalArgumentException("Preview work key mismatch");
        return startTask(() -> TerrainPreviewFrame.assemble(key, work == null ? null : work.bake(), store, previous));
    }

    /** HUD requests just L0; it does not build/restore an overview or write storage. */
    static @Nullable TerrainPreviewJob startDetail(TerrainPreview.Work work, int @Nullable [] history) {
        return startTask(() -> {
            int[][] mips = Objects.requireNonNull(
                work.bake()
                    .mips(),
                "full tile");
            return new TerrainPreviewFrame(PreviewTileStore.retain(history, mips[0]), null, null, false);
        });
    }

    /** Call only after accepting the frame on the client thread under its epoch and refresh revision. */
    static @Nullable TerrainPreviewJob startSave(PreviewTileStore store, TileKey key, TerrainPreviewFrame accepted) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        if (!accepted.needsSave()) throw new IllegalArgumentException("No fresh pixels to save");
        return startTask(() -> {
            try {
                accepted.save(store, key);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            return null;
        }, true);
    }

    // Package-private injection only for pure latch-based probes, never native callbacks.
    static @Nullable TerrainPreviewJob startTask(Supplier<TerrainPreviewFrame> task) {
        return startTask(task, false);
    }

    private static synchronized @Nullable TerrainPreviewJob startTask(Supplier<TerrainPreviewFrame> task,
        boolean acceptedWrite) {
        Objects.requireNonNull(task, "task");
        if (isBusy()) return null;
        TerrainPreviewJob job = new TerrainPreviewJob(task, acceptedWrite);
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
        Supplier<TerrainPreviewFrame> input;
        synchronized (this) {
            input = this.task;
            this.task = null;
        }
        TerrainPreviewFrame output = null;
        String error = null;
        try {
            if (input != null) {
                output = input.get();
                if (!this.acceptedWrite) Objects.requireNonNull(output, "preview result");
            }
        } catch (Throwable failed) {
            error = failed.getClass()
                .getSimpleName();
        } finally {
            // The terminating daemon does not retain snapshots, Looks or exceptions.
            input = null;
            synchronized (this) {
                if (!this.discarded) {
                    this.frame = output;
                    this.failure = error;
                }
                this.task = null;
                this.done = true;
            }
        }
    }

    public synchronized boolean done() {
        return this.done && !this.thread.isAlive();
    }

    public synchronized String failure() {
        return this.failure;
    }

    public synchronized @Nullable TerrainPreviewFrame takeFrame() {
        TerrainPreviewFrame result = this.frame;
        this.frame = null;
        return result;
    }

    @Override
    public synchronized void close() {
        this.discarded = true;
        // Closing a screen cancels a speculative bake, not an already accepted commit.
        if (!this.acceptedWrite) this.task = null;
        this.frame = null;
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
