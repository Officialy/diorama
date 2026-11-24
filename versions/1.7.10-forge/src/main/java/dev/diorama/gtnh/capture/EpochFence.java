package dev.diorama.gtnh.capture;

import org.jspecify.annotations.Nullable;

/**
 * Owner-thread lifecycle fence, independent of game objects and resource-manager identity.
 * Atlas notifications must describe the block atlas. A HEAD without its successful RETURN leaves
 * its depth outstanding: later fallback reloads cannot cancel it. Recovery requires a new fence.
 * Readiness requires a complete outer reload and an atlas return after the latest reload start,
 * including reentrant starts; an earlier atlas return cannot certify later resource mutations.
 * Standalone stitching only invalidates readiness; a later complete reload is required.
 * This is not a worker-thread publication or JMM synchronization primitive.
 */
public final class EpochFence {

    private final Thread ownerThread = Thread.currentThread();
    private int reloadDepth;
    private int atlasDepth;
    private boolean worldAvailable;
    private boolean hasCompletedAtlas;
    private boolean resourcesReady;
    private boolean faulted;
    private @Nullable Object token;

    private void requireOwner() {
        if (Thread.currentThread() != this.ownerThread)
            throw new IllegalStateException("Epoch fence requires its owner thread");
    }

    public void beginReload() {
        this.requireOwner();
        this.token = null;
        if (this.reloadDepth == Integer.MAX_VALUE) {
            this.faulted = true;
            throw new IllegalStateException("Reload depth overflow");
        }
        // An in-flight atlas may already have consumed resources from before this mutation.
        if (this.atlasDepth > 0) this.faulted = true;
        // Even a reentrant reload can change resources after an earlier atlas return.
        this.hasCompletedAtlas = false;
        this.resourcesReady = false;
        this.reloadDepth++;
    }

    public void endReload() {
        this.requireOwner();
        this.token = null;
        if (this.reloadDepth == 0) {
            this.faulted = true;
            throw new IllegalStateException("Unmatched reload end");
        }
        this.reloadDepth--;
        if (this.reloadDepth == 0 && this.atlasDepth == 0 && this.hasCompletedAtlas) this.resourcesReady = true;
    }

    public void beginAtlas() {
        this.requireOwner();
        this.token = null;
        if (this.atlasDepth == Integer.MAX_VALUE) {
            this.faulted = true;
            throw new IllegalStateException("Atlas depth overflow");
        }
        this.resourcesReady = false;
        this.atlasDepth++;
    }

    public void endAtlas() {
        this.requireOwner();
        this.token = null;
        if (this.atlasDepth == 0) {
            this.faulted = true;
            throw new IllegalStateException("Unmatched atlas end");
        }
        this.atlasDepth--;
        this.hasCompletedAtlas = true;
    }

    /** Every notification invalidates the epoch, including reconnects to the same dimension. */
    public void worldChanged(boolean available) {
        this.requireOwner();
        this.token = null;
        this.worldAvailable = available;
    }

    /**
     * Revokes readiness at a public resource mutation HEAD without claiming a completed reload.
     * Does not consume or repair outstanding lifecycle depths. With no active atlas, a complete
     * reload can recover; mutating resources during an active atlas permanently faults this fence.
     */
    public void invalidateResources() {
        this.requireOwner();
        this.token = null;
        this.resourcesReady = false;
        this.hasCompletedAtlas = false;
        if (this.atlasDepth > 0) this.faulted = true;
    }

    /** Permanently blocks this coordinator when a native caller detects a lifecycle failure. */
    public void failClosed() {
        this.requireOwner();
        this.token = null;
        this.faulted = true;
    }

    private boolean ready() {
        return this.worldAvailable && this.resourcesReady
            && this.hasCompletedAtlas
            && this.reloadDepth == 0
            && this.atlasDepth == 0
            && !this.faulted;
    }

    /** The opaque plain Object never holds a live world, resource, manager, or owner thread. */
    public @Nullable Object session() {
        this.requireOwner();
        if (!this.ready()) return null;
        if (this.token == null) this.token = new Object();
        return this.token;
    }

    public boolean current(@Nullable Object candidate) {
        this.requireOwner();
        return candidate != null && candidate == this.token && this.ready();
    }
}
