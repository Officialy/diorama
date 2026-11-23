package dev.diorama.gtnh.capture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import dev.diorama.gtnh.capture.TerrainMetrics.Counter;
import dev.diorama.gtnh.capture.TerrainMetrics.FrameView;
import dev.diorama.gtnh.capture.TerrainMetrics.Phase;

/** Standalone fixture for frame cadence percentiles and the shared report window. */
public final class TerrainMetricsProbe {

    private static final long WINDOW = 5_000_000_000L;
    private static final long MS = 1_000_000L;

    private TerrainMetricsProbe() {}

    public static void main(String[] args) throws Exception {
        boolean enabled = args.length == 0;
        System.setProperty("diorama.terrainMetrics", Boolean.toString(enabled));
        check(TerrainMetrics.ENABLED == enabled, "property read at TerrainMetrics initialization");
        if (!enabled) {
            TerrainMetrics.recordFrame(FrameView.HUD, MS);
            check(TerrainMetrics.reportAfterElapsed(WINDOW) == null, "disabled samples not reported");
            check(TerrainMetrics.reportIfDue() == null, "disabled production report");
            System.out.println("PASS terrain frame metrics disabled");
            return;
        }

        for (int i = 0; i < 95; i++) TerrainMetrics.recordFrame(FrameView.HUD, 11 * MS + 1);
        for (int i = 0; i < 4; i++) TerrainMetrics.recordFrame(FrameView.HUD, 17 * MS);
        TerrainMetrics.recordFrame(FrameView.HUD, 200 * MS);
        TerrainMetrics.recordFrame(FrameView.HUD, -1);
        TerrainMetrics.recordFrame(FrameView.HUD_HIDDEN, 0);
        TerrainMetrics.recordFrame(FrameView.MAP, 250 * MS);
        TerrainMetrics.recordFrame(FrameView.MAP, 250 * MS + 1);
        TerrainMetrics.recordFrame(FrameView.MAP, Long.MAX_VALUE);
        TerrainMetrics.record(Phase.HUD_BAKE, 5);
        TerrainMetrics.add(Counter.CAPTURE_READY, 2);
        check(TerrainMetrics.reportAfterElapsed(WINDOW - 1) == null, "no early report or reset");

        String report = TerrainMetrics.reportAfterElapsed(WINDOW);
        String expected = "window_ns=5000000000 HUD_BAKE.count=1 HUD_BAKE.total_ns=5 HUD_BAKE.max_ns=5 CAPTURE_READY=2"
            + " FRAME_HUD.count=100 FRAME_HUD.max_ns=200000000 FRAME_HUD.p95_upper_ns=12000000 FRAME_HUD.p99_upper_ns=17000000"
            + " FRAME_HUD_HIDDEN.count=1 FRAME_HUD_HIDDEN.max_ns=0 FRAME_HUD_HIDDEN.p95_upper_ns=0 FRAME_HUD_HIDDEN.p99_upper_ns=0"
            + " FRAME_MAP.count=3 FRAME_MAP.max_ns=9223372036854775807 FRAME_MAP.p95_upper_ns=9223372036854775807"
            + " FRAME_MAP.p99_upper_ns=9223372036854775807";
        check(expected.equals(report), "isolated counts, nearest ranks, zero, and overflow: " + report);

        for (int i = 0; i < 99; i++) TerrainMetrics.recordFrame(FrameView.MAP, 250 * MS);
        TerrainMetrics.recordFrame(FrameView.MAP, 250 * MS + 1);
        String next = TerrainMetrics.reportAfterElapsed(WINDOW);
        check(
            ("window_ns=5000000000 FRAME_MAP.count=100 FRAME_MAP.max_ns=250000001"
                + " FRAME_MAP.p95_upper_ns=250000000 FRAME_MAP.p99_upper_ns=250000000").equals(next),
            "drain clears counts, maxima, and bins; bounded percentile can exclude overflow: " + next);
        check(TerrainMetrics.reportAfterElapsed(WINDOW) == null, "idle windows omitted");

        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest((report + "\n" + next).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS terrain frame metrics: view isolation, bucket boundaries, nearest ranks, overflow and reset");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
