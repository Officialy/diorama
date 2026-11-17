package dev.diorama.bake;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Append-only, owner-thread preparation of explicitly audited cubes and leaves. This is not a block
 * classifier: the client producer must prove geometry, texture and context independence before
 * preparation. Workers only consume published Looks arrays; no game callbacks are retained here.
 */
public final class PreparedBlockLooks extends Looks {

    private final Object session;
    private final Thread owner = Thread.currentThread();
    private final Map<Long, Integer> ids = new HashMap<>();
    private int next = 1;
    private boolean active = true;

    public PreparedBlockLooks(Object session, int capacity) {
        super(checkedCapacity(capacity), capacity);
        this.session = Objects.requireNonNull(session, "session");
        Arrays.fill(this.constantTint, -1);
        this.publish(0, AIR);
    }

    private static int checkedCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Appearance capacity must include air");
        return capacity;
    }

    public Object session() {
        return this.session;
    }

    public int airId() {
        return 0;
    }

    private void requireOwner() {
        if (Thread.currentThread() != this.owner)
            throw new IllegalStateException("Appearance preparation requires its owner thread");
    }

    private static long key(int rawId, int metadata) {
        if (rawId < 0 || metadata < 0 || metadata > 65535)
            throw new IllegalArgumentException("Invalid raw block ID or 16-bit metadata");
        // Widen before shifting: all 31 nonnegative raw ID bits and 16 metadata bits are distinct.
        return ((long) rawId << 16) | metadata;
    }

    /** Only allocated and published entries are returned. Missing/invalidated entries stay pending. */
    public int find(int rawId, int metadata) {
        this.requireOwner();
        long key = key(rawId, metadata);
        return this.active ? this.ids.getOrDefault(key, -1) : -1;
    }

    /**
     * Publishes an opaque cube from canonical SpriteAverage output. A failed or full preparation does
     * not allocate an ID. Texture alpha/geometry auditing remains the producer's responsibility.
     */
    public int cube(int rawId, int metadata, float[] top, float[] side, int emission) {
        this.requireOwner();
        long key = key(rawId, metadata);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkFace(top);
        checkFace(side);
        if (emission < 0 || emission > 15) throw new IllegalArgumentException("Invalid block light emission");
        FaceColor topFace = new FaceColor();
        FaceColor sideFace = new FaceColor();
        topFace.add(top, -1);
        sideFace.add(side, -1);
        return this.commit(key, topFace, sideFace, emission, -1, TINT_NONE);
    }

    /** Publishes audited cutout leaves; coverage never converts leaves into see-through glass. */
    public int leaves(int rawId, int metadata, float[] average, int constantTint) {
        this.requireOwner();
        long key = key(rawId, metadata);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkAverage(average);
        if (constantTint < -1 || constantTint > 0xFFFFFF)
            throw new IllegalArgumentException("Invalid constant foliage tint");
        FaceColor face = new FaceColor();
        face.add(average, 0);
        return this.commit(key, face, face, 0, constantTint, constantTint == -1 ? TINT_FOLIAGE : TINT_NONE);
    }

    // Zero is reserved air, so it can signal a new key without allocating or changing payload.
    private int existingId(long key) {
        if (!this.active) return -1;
        return this.ids.getOrDefault(key, 0);
    }

    private int commit(long key, FaceColor top, FaceColor side, int emission, int constantTint, byte tint) {
        if (this.next == this.kind.length) return -1;
        int id = this.next;
        this.setColours(id, top, side);
        this.emission[id] = (byte) emission;
        this.constantTint[id] = constantTint;
        this.tint[id] = tint;
        // Spawn rules are deliberately not inferred: legacy spawn overlays are not enabled.
        this.publish(id, SOLID);
        this.ids.put(key, id);
        this.next++;
        return id;
    }

    private static void checkFace(float[] average) {
        checkAverage(average);
        if (average[3] != 1.0F) throw new IllegalArgumentException("Only complete opaque cube faces are supported");
    }

    private static void checkAverage(float[] average) {
        Objects.requireNonNull(average, "average");
        if (average.length != 4 || !Float.isFinite(average[3]) || average[3] <= 0 || average[3] > 1)
            throw new IllegalArgumentException("Invalid cutout coverage");
        for (int i = 0; i < 3; i++) {
            if (!Float.isFinite(average[i]) || average[i] < 0 || average[i] > 1)
                throw new IllegalArgumentException("Invalid average colour");
        }
    }

    /** Old payloads remain immutable for in-flight readers; result acceptance must reject this epoch. */
    public void invalidate() {
        this.requireOwner();
        this.active = false;
        this.ids.clear();
    }

    @Override
    protected void make(int id) {
        throw new IllegalStateException("Appearance must be explicitly prepared before worker use");
    }
}
