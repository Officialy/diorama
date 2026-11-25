package dev.diorama.gtnh.client;

import java.util.Arrays;
import java.util.BitSet;

import dev.diorama.gtnh.capture.TerrainMetrics;

/** Owner-thread, distance-prioritized refresh queue; no live-world access. */
final class TerrainPreviewRefresh {

    static final int SLOTS = 9;
    static final int MAX_RADIUS = 32;
    static final long CHANGE = 250_000_000L;
    static final long LOAD = 750_000_000L;
    static final int CAPTURES_PER_TICK = 24;
    static final int DISCOVERY_TICKS = 40;

    private final int centerX, centerZ, radius, edge, slots;
    private final long[] deadlines, versions;
    private final int[] order;
    private final BitSet queued;
    private long revision;

    TerrainPreviewRefresh(int centerX, int centerZ, long now) {
        this(centerX, centerZ, 1, now);
    }

    TerrainPreviewRefresh(int centerX, int centerZ, int radius, long now) {
        if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Invalid capture radius");
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        edge = radius * 2 + 1;
        slots = edge * edge;
        deadlines = new long[slots];
        versions = new long[slots];
        queued = new BitSet(slots);
        order = new int[slots];
        Arrays.fill(deadlines, now);
        queued.set(0, slots);
        // Preserve the original nine-slot diagnostic ordering. Larger windows capture near first.
        int next = 0;
        if (radius == 1) {
            for (int i = 0; i < slots; i++) order[next++] = i;
        } else {
            for (int ring = 0; ring <= radius; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == ring)
                            order[next++] = (dz + radius) * edge + dx + radius;
                    }
                }
            }
        }
    }

    int slots() {
        return slots;
    }

    int radius() {
        return radius;
    }

    int slot(int chunkX, int chunkZ) {
        long dx = (long) chunkX - centerX, dz = (long) chunkZ - centerZ;
        return dx < -radius || dx > radius || dz < -radius || dz > radius ? -1
            : (int) ((dz + radius) * edge + dx + radius);
    }

    int x(int slot) {
        return centerX + slot % edge - radius;
    }

    int z(int slot) {
        return centerZ + slot / edge - radius;
    }

    void request(int slot, long now, long delay) {
        if (slot < 0) return;
        long due = now + delay;
        boolean already = queued.get(slot);
        if (TerrainMetrics.ENABLED) TerrainMetrics
            .add(already ? TerrainMetrics.Counter.REFRESH_COALESCED : TerrainMetrics.Counter.REFRESH_ENQUEUED, 1);
        if (!already || due - deadlines[slot] < 0) deadlines[slot] = due;
        queued.set(slot);
        versions[slot]++;
        revision++;
    }

    void inherit(int slot, TerrainPreviewRefresh previous, int priorSlot) {
        if (previous.queued(priorSlot)) deadlines[slot] = previous.deadlines[priorSlot];
        else queued.clear(slot);
    }

    boolean queued(int slot) {
        return queued.get(slot);
    }

    boolean pending() {
        return !queued.isEmpty();
    }

    long revision() {
        return revision;
    }

    long version(int slot) {
        return versions[slot];
    }

    /** Original diagnostic interface. */
    int takeDue(long now, int attempted) {
        return takeDue(now, BitSet.valueOf(new long[] { Integer.toUnsignedLong(attempted) }));
    }

    /** Detach a due request before native callbacks can queue another one. */
    int takeDue(long now, BitSet attempted) {
        for (int slot : order) {
            if (queued.get(slot) && !attempted.get(slot) && now - deadlines[slot] >= 0) {
                queued.clear(slot);
                if (TerrainMetrics.ENABLED) {
                    TerrainMetrics.record(TerrainMetrics.Phase.REFRESH_QUEUE_WAIT, now - deadlines[slot]);
                    TerrainMetrics.add(TerrainMetrics.Counter.REFRESH_DEQUEUED, 1);
                }
                return slot;
            }
        }
        return -1;
    }
}
