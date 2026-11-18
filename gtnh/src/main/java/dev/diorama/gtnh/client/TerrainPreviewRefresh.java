package dev.diorama.gtnh.client;

import java.util.Arrays;

/** Owner-thread queue for one fixed 3x3 preview, using monotonic nanosecond deadlines. */
final class TerrainPreviewRefresh {

    static final int SLOTS = 9;
    static final long CHANGE = 250_000_000L;
    static final long LOAD = 750_000_000L;
    static final int CAPTURES_PER_TICK = 24;
    static final int DISCOVERY_TICKS = 40;

    private final int centerX, centerZ;
    private final long[] deadlines = new long[SLOTS];
    private final long[] versions = new long[SLOTS];
    private int queued;
    private long revision;

    TerrainPreviewRefresh(int centerX, int centerZ, long now) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        Arrays.fill(deadlines, now);
        queued = (1 << SLOTS) - 1; // Explicit opening capture; not a load event.
    }

    int slot(int chunkX, int chunkZ) {
        long dx = (long) chunkX - centerX, dz = (long) chunkZ - centerZ;
        return dx < -1 || dx > 1 || dz < -1 || dz > 1 ? -1 : (int) ((dz + 1) * 3 + dx + 1);
    }

    int x(int slot) {
        return centerX + slot % 3 - 1;
    }

    int z(int slot) {
        return centerZ + slot / 3 - 1;
    }

    void request(int slot, long now, long delay) {
        if (slot < 0) return;
        long due = now + delay;
        int bit = 1 << slot;
        if ((queued & bit) == 0 || due - deadlines[slot] < 0) deadlines[slot] = due;
        queued |= bit;
        versions[slot]++;
        revision++;
    }

    boolean queued(int slot) {
        return (queued & (1 << slot)) != 0;
    }

    boolean pending() {
        return queued != 0;
    }

    long revision() {
        return revision;
    }

    long version(int slot) {
        return versions[slot];
    }

    /** Detach the next due request before any native read/callback can queue another one. */
    int takeDue(long now, int attempted) {
        int candidates = queued & ~attempted;
        for (int slot = 0; slot < SLOTS; slot++) {
            int bit = 1 << slot;
            if ((candidates & bit) != 0 && now - deadlines[slot] >= 0) {
                queued &= ~bit;
                return slot;
            }
        }
        return -1;
    }
}
