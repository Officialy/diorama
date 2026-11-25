package dev.diorama.bake;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.gtnh.capture.GrassContext;

/**
 * Append-only, owner-thread preparation of audited cubes, leaves, grass contexts, water, grass plants
 * and snow layers. This is not a classifier: the client producer must prove geometry, texture and
 * the applicable context before preparation. Workers only consume published Looks arrays; no game
 * callbacks are retained here.
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
        return key(rawId, metadata, 0);
    }

    private static long key(int rawId, int metadata, int context) {
        if (rawId < 0 || metadata < 0 || metadata > 65535 || context < 0 || context > 3)
            throw new IllegalArgumentException("Invalid raw block ID or 16-bit metadata");
        // Widen before shifting: 31 raw-ID bits, 16 metadata bits and explicit context never collide.
        return ((long) rawId << 18) | ((long) metadata << 2) | context;
    }

    /** Only allocated and published entries are returned. Missing/invalidated entries stay pending. */
    public int find(int rawId, int metadata) {
        return this.find(rawId, metadata, 0);
    }

    public int find(int rawId, int metadata, int context) {
        this.requireOwner();
        if (context > GrassContext.SNOWY) throw new IllegalArgumentException("Invalid grass context");
        long key = key(rawId, metadata, context);
        return this.active ? this.ids.getOrDefault(key, -1) : -1;
    }

    /** Renderer-observed geometry has its own key, separate from the public grass contexts. */
    public int findObserved(int rawId, int metadata) {
        this.requireOwner();
        long key = key(rawId, metadata, 3);
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

    /** Grass uses distinct context keys; the top stays tinted even under snow. */
    public int grass(int rawId, int metadata, int context, float[] top, float[] side, float @Nullable [] overlay) {
        this.requireOwner();
        if (context != GrassContext.NORMAL && context != GrassContext.SNOWY)
            throw new IllegalArgumentException("Grass requires an explicit normal or snowy context");
        long key = key(rawId, metadata, context);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkFace(top);
        checkFace(side);
        FaceColor topFace = new FaceColor();
        FaceColor sideFace = new FaceColor();
        topFace.add(top, 0);
        sideFace.add(side, -1);
        if (context == GrassContext.NORMAL) {
            Objects.requireNonNull(overlay, "overlay");
            if (overlay.length != 4 || !Float.isFinite(overlay[3]) || overlay[3] < 0 || overlay[3] > 1)
                throw new IllegalArgumentException("Invalid grass overlay coverage");
            for (int i = 0; i < 3; i++) if (!Float.isFinite(overlay[i]) || overlay[i] < 0 || overlay[i] > 1)
                throw new IllegalArgumentException("Invalid grass overlay colour");
            // Canonical quad coverage-weighted representative colour, not pixel alpha compositing.
            sideFace.add(overlay, 0);
        } else if (overlay != null) throw new IllegalArgumentException("Snowy sides cannot carry a tinted overlay");
        return this.commit(key, topFace, sideFace, 0, -1, TINT_GRASS);
    }

    /**
     * Canonical water is a liquid medium, not an opaque cube or a legacy sloped surface. All legacy
     * levels use the still sprite; the shared tracer owns cell entry, depth and water blending.
     */
    public int water(int rawId, int metadata, float[] average) {
        this.requireOwner();
        if (metadata < 0 || metadata > 15) throw new IllegalArgumentException("Invalid vanilla water metadata");
        long key = key(rawId, metadata);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkAverage(average);
        FaceColor face = new FaceColor();
        face.add(average, 0);
        return this.commit(key, face, face, 0, -1, TINT_WATER, WATER);
    }

    /** No-collision crossed plants use the shared PLANT ray treatment, not a solid outline. */
    public int grassPlant(int rawId, int metadata, float[] average) {
        this.requireOwner();
        if (metadata != 1 && metadata != 2) throw new IllegalArgumentException("Invalid grass or fern metadata");
        long key = key(rawId, metadata);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkAverage(average);
        FaceColor face = new FaceColor();
        face.add(average, 0);
        return this.commit(key, face, face, 0, -1, TINT_GRASS, PLANT);
    }

    /** Snow remains solid even when its collision is empty: use its outline, not its collision height. */
    public int snowLayer(int rawId, int metadata, float[] average) {
        this.requireOwner();
        if (metadata < 0 || metadata > 7) throw new IllegalArgumentException("Invalid snow layer metadata");
        long key = key(rawId, metadata);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkFace(average);
        if (this.next == this.kind.length) return -1;
        int shapeIndex = metadata == 7 ? 0 : this.internShape(new float[] { 0, 0, 0, 1, (metadata + 1) / 8F, 1 });
        FaceColor face = new FaceColor();
        face.add(average, -1);
        return this.commit(key, face, face, 0, -1, TINT_NONE, SOLID, shapeIndex);
    }

    /** Publish approximate legacy geometry and colours; the caller owns no retained arrays. */
    public int approximate(int rawId, int metadata, float[] top, float[] side, int emission, byte kind,
        float @Nullable [] boxes) {
        return this.approximate(rawId, metadata, 0, top, side, emission, kind, boxes);
    }

    /** Context 3 is the experimental renderer-observed representative geometry. */
    public int approximate(int rawId, int metadata, int context, float[] top, float[] side, int emission, byte kind,
        float @Nullable [] boxes) {
        this.requireOwner();
        if (context != 0 && context != 3) throw new IllegalArgumentException("Invalid approximation context");
        long key = key(rawId, metadata, context);
        int existing = this.existingId(key);
        if (existing != 0) return existing;
        checkAverage(top);
        checkAverage(side);
        if (emission < 0 || emission > 15 || kind < SOLID || kind > PLANT || kind == WATER)
            throw new IllegalArgumentException("Invalid approximate material");
        if (boxes != null) {
            if (kind != SOLID || boxes.length == 0 || boxes.length % 6 != 0 || boxes.length > 96)
                throw new IllegalArgumentException("Invalid approximate shape");
            for (int i = 0; i < boxes.length; i += 6) {
                for (int axis = 0; axis < 3; axis++) {
                    float lo = boxes[i + axis], hi = boxes[i + axis + 3];
                    if (!Float.isFinite(lo) || !Float.isFinite(hi) || lo < 0 || hi > 1 || lo >= hi)
                        throw new IllegalArgumentException("Invalid approximate bounds");
                }
            }
        }
        if (this.next == this.kind.length) return -1;
        int shapeIndex = boxes == null ? 0 : this.internShape(boxes.clone());
        FaceColor topFace = new FaceColor();
        FaceColor sideFace = new FaceColor();
        topFace.add(top, -1);
        sideFace.add(side, -1);
        return this.commit(key, topFace, sideFace, emission, -1, TINT_NONE, kind, shapeIndex);
    }

    // Zero is reserved air, so it can signal a new key without allocating or changing payload.
    private int existingId(long key) {
        if (!this.active) return -1;
        return this.ids.getOrDefault(key, 0);
    }

    private int commit(long key, FaceColor top, FaceColor side, int emission, int constantTint, byte tint) {
        return this.commit(key, top, side, emission, constantTint, tint, SOLID);
    }

    private int commit(long key, FaceColor top, FaceColor side, int emission, int constantTint, byte tint, byte kind) {
        return this.commit(key, top, side, emission, constantTint, tint, kind, 0);
    }

    private int commit(long key, FaceColor top, FaceColor side, int emission, int constantTint, byte tint, byte kind,
        int shapeIndex) {
        if (this.next == this.kind.length) return -1;
        int id = this.next;
        this.setColours(id, top, side);
        if (kind == WATER) {
            // Match BlockLooks.colours: both slots retain the still colour with tint weight one.
            this.top[id] = this.topTinted[id];
            this.side[id] = this.sideTinted[id];
        }
        this.emission[id] = (byte) emission;
        this.constantTint[id] = constantTint;
        this.tint[id] = tint;
        this.shape[id] = shapeIndex;
        // Spawn rules are deliberately not inferred: legacy spawn overlays are not enabled.
        this.publish(id, kind);
        this.ids.put(key, id);
        this.next++;
        return id;
    }

    private static void checkFace(float[] average) {
        checkAverage(average);
        if (average[3] != 1.0F) throw new IllegalArgumentException("Only complete opaque faces are supported");
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
