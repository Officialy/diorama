package dev.diorama.gtnh.capture;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/** Process-wide GTNH terrain timing and counts, enabled by -Ddiorama.terrainMetrics=true. */
public final class TerrainMetrics {

    public static final boolean ENABLED = Boolean.getBoolean("diorama.terrainMetrics");

    private TerrainMetrics() {}

    public enum Phase {
        CAPTURE_SLOT,
        CAPTURE_COPY,
        CAPTURE_APPEARANCE,
        CAPTURE_RESOLVE,
        CAPTURE_TINT,
        // Lateness after the refresh deadline, excluding intentional debounce.
        REFRESH_QUEUE_WAIT,
        // From immediately before starting the worker thread to entry into its run method.
        WORKER_QUEUE_WAIT,
        HUD_BAKE,
        MAP_BAKE,
        // CPU wall time inside PreviewUpload.transfer; does not wait for the GPU.
        HUD_UPLOAD,
        MAP_UPLOAD
    }

    public enum Counter {
        CAPTURE_READY,
        CAPTURE_MISSING,
        CAPTURE_PENDING,
        CAPTURE_STALE,
        CAPTURE_FAILED,
        CAPTURE_STATE_CELLS,
        CAPTURE_UNKNOWN_LOOK_CELLS,
        GEOMETRY_OBSERVED,
        GEOMETRY_WORKER_OBSERVED,
        CAPTURE_PENDING_TINT,
        CAPTURE_LIGHT_CELLS,
        REFRESH_ENQUEUED,
        REFRESH_COALESCED,
        REFRESH_DEQUEUED,
        HUD_UPLOAD_PUBLISHED,
        MAP_UPLOAD_PUBLISHED,
        HUD_UPLOAD_REJECTED,
        MAP_UPLOAD_REJECTED,
        HUD_UPLOAD_FAILED,
        MAP_UPLOAD_FAILED
    }

    public enum FrameView {
        HUD,
        HUD_HIDDEN,
        MAP
    }

    /** Record a cadence interval between successive frames in the same view. Negative samples are ignored. */
    public static void recordFrame(FrameView view, long nanos) {
        if (ENABLED && nanos >= 0) Aggregate.recordFrame(view, nanos);
    }

    /** Guard the timestamps and this call with ENABLED at the probe site. */
    public static void record(Phase phase, long elapsedNanos) {
        if (ENABLED) Aggregate.record(phase, elapsedNanos);
    }

    /** Guard the probe site with ENABLED, especially if computing the count requires extra work. */
    public static void add(Counter counter, long count) {
        if (ENABLED) Aggregate.add(counter, count);
    }

    /** Call on the client tick, including when the HUD is not visible. Idle intervals are omitted. */
    public static @Nullable String reportIfDue() {
        return ENABLED ? Aggregate.drainIfDue(System.nanoTime()) : null;
    }

    // Standalone probe seam: exercise the same drain logic without waiting on the wall clock.
    static @Nullable String reportAfterElapsed(long elapsedNanos) {
        return ENABLED ? Aggregate.drainAfterElapsed(elapsedNanos) : null;
    }

    // Lazy initialization: the disabled path never initializes the accumulator.
    private static final class Aggregate {

        private static final long INTERVAL_NS = 5_000_000_000L;
        // 1 ms upper bounds through 250 ms, plus exact zero and a final overflow bin.
        private static final long FRAME_BUCKET_NS = 1_000_000L;
        private static final int FRAME_OVERFLOW = 251;
        private static final FrameView[] FRAME_VIEWS = FrameView.values();
        private static final long[][] frameBuckets = new long[FRAME_VIEWS.length][FRAME_OVERFLOW + 1];
        private static final long[] frameCounts = new long[FRAME_VIEWS.length];
        private static final long[] frameMaxima = new long[FRAME_VIEWS.length];
        private static final Phase[] PHASES = Phase.values();
        private static final Counter[] COUNTERS = Counter.values();
        private static final long[] counts = new long[PHASES.length];
        private static final long[] totals = new long[PHASES.length];
        private static final long[] maxima = new long[PHASES.length];
        private static final long[] counters = new long[COUNTERS.length];
        private static long since = System.nanoTime();

        private static synchronized void record(Phase phase, long elapsedNanos) {
            int i = phase.ordinal();
            counts[i]++;
            totals[i] += elapsedNanos;
            maxima[i] = Math.max(maxima[i], elapsedNanos);
        }

        private static synchronized void recordFrame(FrameView view, long nanos) {
            int i = view.ordinal();
            if (frameCounts[i] == Long.MAX_VALUE) return;
            // Subtract before rounding, then clamp before narrowing; even Long.MAX_VALUE is safe.
            int bucket = nanos == 0 ? 0 : (int) Math.min(FRAME_OVERFLOW, (nanos - 1) / FRAME_BUCKET_NS + 1);
            frameBuckets[i][bucket]++;
            frameCounts[i]++;
            frameMaxima[i] = Math.max(frameMaxima[i], nanos);
        }

        private static synchronized void add(Counter counter, long count) {
            counters[counter.ordinal()] += count;
        }

        private static synchronized @Nullable String drainAfterElapsed(long elapsedNanos) {
            return drainIfDue(since + elapsedNanos);
        }

        private static long frameUpperBound(int view, long rank) {
            long seen = 0;
            for (int bucket = 0; bucket < FRAME_OVERFLOW; bucket++) {
                seen += frameBuckets[view][bucket];
                if (seen >= rank) return bucket * FRAME_BUCKET_NS;
            }
            // Overflow samples still participate in the percentile, with the observed max as their bound.
            return frameMaxima[view];
        }

        private static synchronized @Nullable String drainIfDue(long now) {
            long elapsed = now - since;
            if (elapsed < INTERVAL_NS) return null;
            since = now;
            StringBuilder report = new StringBuilder();
            for (Phase phase : PHASES) {
                int i = phase.ordinal();
                if (counts[i] == 0) continue;
                report.append(' ')
                    .append(phase)
                    .append(".count=")
                    .append(counts[i])
                    .append(' ')
                    .append(phase)
                    .append(".total_ns=")
                    .append(totals[i])
                    .append(' ')
                    .append(phase)
                    .append(".max_ns=")
                    .append(maxima[i]);
                counts[i] = totals[i] = maxima[i] = 0;
            }
            for (Counter counter : COUNTERS) {
                int i = counter.ordinal();
                if (counters[i] == 0) continue;
                report.append(' ')
                    .append(counter)
                    .append('=')
                    .append(counters[i]);
                counters[i] = 0;
            }
            for (FrameView view : FRAME_VIEWS) {
                int i = view.ordinal();
                long count = frameCounts[i];
                if (count == 0) continue;
                String prefix = " FRAME_" + view;
                // Nearest ranks ceil(0.95 * count), ceil(0.99 * count), with no multiplication overflow.
                report.append(prefix)
                    .append(".count=")
                    .append(count)
                    .append(prefix)
                    .append(".max_ns=")
                    .append(frameMaxima[i])
                    .append(prefix)
                    .append(".p95_upper_ns=")
                    .append(frameUpperBound(i, count - count / 20))
                    .append(prefix)
                    .append(".p99_upper_ns=")
                    .append(frameUpperBound(i, count - count / 100));
                Arrays.fill(frameBuckets[i], 0);
                frameCounts[i] = frameMaxima[i] = 0;
            }
            return report.length() == 0 ? null : "window_ns=" + elapsed + report;
        }
    }
}
