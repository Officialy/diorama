package dev.diorama.gtnh.client;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewTileStore;

/** Session-scoped OMT restore and best-effort persistence, independent of the terrain bake worker. */
final class SharedTileIo {

    private static final Logger LOG = Logger.getLogger("Diorama");
    static final int READ_LIMIT = 8;
    static final int WRITE_LIMIT = 8;
    private static final int PIXELS = Projection.TILE_PIXELS * Projection.TILE_PIXELS;

    private final Deque<Request> pending = new ArrayDeque<>();
    // Read slots stay occupied until drain, including the completed rasters.
    private final Set<TileKey> reads = new HashSet<>();
    private final Deque<Read> completed = new ArrayDeque<>();
    private final @Nullable Runnable beforeIo;
    private @Nullable PreviewTileStore store;
    private long generation;
    private int writes;
    private boolean started;
    private boolean running;

    SharedTileIo() {
        this(null);
    }

    // The detached probe can stop an already dequeued request before it touches disk.
    SharedTileIo(@Nullable Runnable beforeIo) {
        this.beforeIo = beforeIo;
    }

    /** Main thread only. Drops speculative reads but leaves accepted writes on their original store. */
    synchronized void reset(@Nullable PreviewTileStore store) {
        this.store = store;
        this.generation++;
        this.pending.removeIf(request -> request.pixels == null);
        this.reads.clear();
        this.completed.clear();
    }

    /** Main thread only. False means storage is disabled or another read must be drained first. */
    synchronized boolean read(TileKey key) {
        PreviewTileStore target = this.store;
        if (target == null) return false;
        checkKey(key);
        if (this.reads.contains(key)) return true;
        if (this.reads.size() == READ_LIMIT) return false;
        this.startWorker();
        this.reads.add(key);
        this.pending.addLast(new Request(target, this.generation, key, null));
        this.notifyAll();
        return true;
    }

    /** Main thread only, after accepting the raster. False means the caller has not handed off its write. */
    synchronized boolean write(TileKey key, int[] pixels) {
        PreviewTileStore target = this.store;
        if (target == null) return false;
        checkKey(key);
        if (pixels.length != PIXELS) throw new IllegalArgumentException("Invalid preview raster length");
        for (Request request : this.pending) {
            int[] queued = request.pixels;
            if (queued != null && request.generation == this.generation && request.key.equals(key)) {
                // Only the queue owns this copy. Preserve earlier coverage outside the newest patch.
                for (int i = 0; i < PIXELS; i++) if ((pixels[i] >>> 24) != 0) queued[i] = pixels[i];
                return true;
            }
        }
        if (this.writes == WRITE_LIMIT) return false;
        this.startWorker();
        this.pending.addLast(new Request(target, this.generation, key, pixels.clone()));
        this.writes++;
        this.notifyAll();
        return true;
    }

    /** Main thread only. A null raster without failure is a cache miss. */
    void drain(Consumer<Read> accept) {
        for (int i = 0; i < READ_LIMIT; i++) {
            Read result;
            synchronized (this) {
                result = this.completed.pollFirst();
                if (result == null) return;
                this.reads.remove(result.key());
            }
            accept.accept(result);
        }
    }

    private static void checkKey(TileKey key) {
        if (key.layer() != TileKey.SURFACE || (key.lod() != 0 && key.lod() != 1))
            throw new IllegalArgumentException("Expected surface LOD0 or LOD1 tile");
    }

    private void startWorker() {
        if (this.started) return;
        Thread worker = new Thread(this::run, "Diorama-shared-tile-io");
        worker.setDaemon(true);
        worker.start();
        this.started = true;
    }

    private void run() {
        while (true) {
            Request request;
            synchronized (this) {
                while (this.pending.isEmpty()) {
                    try {
                        this.wait();
                    } catch (InterruptedException ignored) {
                        // World reset is not worker shutdown: accepted writes remain owned here.
                    }
                }
                request = this.pending.removeFirst();
                this.running = true;
            }
            try {
                this.perform(request);
            } finally {
                synchronized (this) {
                    if (request.pixels != null) this.writes--;
                    this.running = false;
                    this.notifyAll();
                }
            }
        }
    }

    private void perform(Request request) {
        int[] fresh = request.pixels;
        if (fresh != null) {
            try {
                if (this.beforeIo != null) this.beforeIo.run();
                if (PreviewTileStore.retain(null, fresh) == null) return;
                int[] disk = request.store.read(request.key);
                request.store.write(request.key, PreviewTileStore.retain(disk, fresh));
            } catch (IOException | RuntimeException failure) {
                // Stale save locks, unsupported files, and ordinary I/O failures are not retried.
                LOG.log(Level.FINE, "Could not persist preview tile " + request.key, failure);
            }
            return;
        }
        Read result;
        try {
            if (this.beforeIo != null) this.beforeIo.run();
            result = new Read(request.key, request.store.read(request.key), null);
        } catch (IOException | RuntimeException failure) {
            result = new Read(request.key, null, failure);
        }
        synchronized (this) {
            if (request.generation == this.generation) this.completed.addLast(result);
        }
    }

    // Probe only: wait for the worker, without draining or admitting additional work.
    synchronized void awaitIdle() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.running || !this.pending.isEmpty()) {
            long remaining = end - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("Shared tile I/O worker did not become idle");
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    record Read(TileKey key, int @Nullable [] pixels, @Nullable Exception failure) {}

    private record Request(PreviewTileStore store, long generation, TileKey key, int @Nullable [] pixels) {}
}
