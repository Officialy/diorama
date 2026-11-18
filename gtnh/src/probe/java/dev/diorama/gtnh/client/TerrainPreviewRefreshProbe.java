package dev.diorama.gtnh.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Deterministic queue/capture interleavings; no sleeps, client or Minecraft classes. */
public final class TerrainPreviewRefreshProbe {

    private static int assertions;
    private static MessageDigest hash;

    private TerrainPreviewRefreshProbe() {}

    public static void main(String[] args) throws Exception {
        hash = MessageDigest.getInstance("SHA-256");
        long now = Long.MAX_VALUE - TerrainPreviewRefresh.CHANGE / 2; // nanoTime may wrap.
        TerrainPreviewRefresh queue = new TerrainPreviewRefresh(-4, 8, now);
        int attempted = 0;
        for (int i = 0; i < TerrainPreviewRefresh.CAPTURES_PER_TICK; i++) {
            int slot = queue.takeDue(now, attempted);
            if (slot < 0) break;
            check(slot == i && queue.slot(queue.x(slot), queue.z(slot)) == slot, "stable nine-slot mapping");
            attempted |= 1 << slot;
        }
        check(attempted == 511 && !queue.pending(), "opening captures exactly nine within canonical budget");
        check(queue.takeDue(now + TerrainPreviewRefresh.LOAD, 0) < 0, "idle does not poll known slots");

        int center = queue.slot(-4, 8);
        check(center == 4 && queue.slot(-2, 8) == -1 && queue.slot(-5, 10) == -1, "strict footprint");
        long acceptedRevision = queue.revision();
        queue.request(queue.slot(-2, 8), now, 0);
        check(queue.revision() == acceptedRevision && !queue.pending(), "unrelated events do not supersede output");

        queue.request(center, now, TerrainPreviewRefresh.LOAD);
        int adjacent = queue.slot(-4, 7);
        queue.request(adjacent, now, TerrainPreviewRefresh.LOAD);
        check(queue.pending() && queue.revision() != acceptedRevision, "new input invalidates in-flight frame");
        long earlierChange = now + 20_000_000L;
        queue.request(center, earlierChange, TerrainPreviewRefresh.CHANGE);
        long changeDue = earlierChange + TerrainPreviewRefresh.CHANGE;
        queue.request(center, earlierChange + 30_000_000L, TerrainPreviewRefresh.CHANGE);
        check(queue.takeDue(changeDue - 1, 0) < 0, "never before the earliest deadline across wrap");
        check(queue.takeDue(changeDue, 0) == center, "change accelerates queued load; later changes do not slide it");
        long captureVersion = queue.version(center);
        long bakeRevision = queue.revision();
        queue.request(center, changeDue, 0); // Reentrant native callback while consuming the detached request.
        check(
            queue.version(center) != captureVersion && queue.revision() != bakeRevision,
            "reject superseded capture and bake");
        check(
            queue.queued(center) && queue.takeDue(changeDue, 1 << center) < 0,
            "reentrant request remains queued past tick budget");
        check(queue.takeDue(changeDue, 0) == center, "next tick processes preserved request");
        check(queue.takeDue(now + TerrainPreviewRefresh.LOAD - 1, 0) < 0, "other slot keeps its load deadline");
        check(
            queue.takeDue(now + TerrainPreviewRefresh.LOAD, 0) == adjacent && !queue.pending(),
            "full load fires at 750ms");
        check(queue.revision() != bakeRevision, "draining a queue cannot restore acceptance for old work");
        long loadAgain = now + TerrainPreviewRefresh.LOAD;
        queue.request(center, loadAgain, TerrainPreviewRefresh.LOAD);
        TerrainPreviewRefresh moved = new TerrainPreviewRefresh(-3, 8, loadAgain + 1);
        int carried = moved.slot(-4, 8);
        int settled = moved.slot(-4, 7);
        moved.inherit(carried, queue, center);
        moved.inherit(settled, queue, adjacent);
        int unrelated = 511 & ~(1 << carried);
        check(
            !moved.queued(settled) && moved.queued(carried),
            "moving reuses settled overlap without dropping pending work");
        check(
            moved.takeDue(loadAgain + TerrainPreviewRefresh.LOAD - 1, unrelated) < 0,
            "moving does not shorten load delay");
        check(
            moved.takeDue(loadAgain + TerrainPreviewRefresh.LOAD, unrelated) == carried,
            "moved request keeps its original deadline");
        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println("PASS preview refresh: " + assertions + " assertions");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        assertions++;
        hash.update(description.getBytes(StandardCharsets.UTF_8));
    }
}
