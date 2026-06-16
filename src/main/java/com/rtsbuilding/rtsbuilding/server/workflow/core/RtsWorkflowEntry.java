package com.rtsbuilding.rtsbuilding.server.workflow.core;

import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowPriority;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowStatus;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A single workflow entry with properly encapsulated mutable state.
 *
 * <p>This replaces the old mutable Entry class which had public fields.
 * fields.  All mutations go through package-private methods that only the
 * engine ({@link RtsWorkflowEngine}) can call — external consumers must use
 * {@link RtsWorkflowToken} or {@link IWorkflowEngine} instead.</p>
 *
 * <p>Each entry has an <b>immutable</b> {@link #id()} that survives index
 * shifts when earlier entries are removed. The {@link #createdAt()} and
 * {@link #lastUpdatedAt()} timestamps enable timeout-based cleanup of
 * zombie workflows.</p>
 */
public final class RtsWorkflowEntry {

    // ──────────────────────────────────────────────────────────────────
    //  Immutable fields
    // ──────────────────────────────────────────────────────────────────

    private final int id;
    private final long createdAt;
    private long lastUpdatedAt;

    // ──────────────────────────────────────────────────────────────────
    //  Mutable fields
    // ──────────────────────────────────────────────────────────────────

    private @Nullable RtsWorkflowType type;
    private RtsWorkflowPriority priority;
    private int totalBlocks;
    private int completedBlocks;
    private int failedBlocks;
    private final List<String> missingItems = new ArrayList<>();
    private String detailMessage = "";
    private boolean suspended;
    private boolean paused;

    // ──────────────────────────────────────────────────────────────────
    //  Construction
    // ──────────────────────────────────────────────────────────────────

    public RtsWorkflowEntry(int id) {
        this.id = id;
        this.priority = RtsWorkflowPriority.NORMAL;
        this.createdAt = System.currentTimeMillis();
        this.lastUpdatedAt = this.createdAt;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Public getters (read-only to outside world)
    // ──────────────────────────────────────────────────────────────────

    /** Unique immutable identifier for this entry within the player's session. */
    public int id() { return id; }

    /** The type of workflow, or {@code null} if this slot is idle. */
    public @Nullable RtsWorkflowType type() { return type; }

    /** The priority level of this workflow. */
    public RtsWorkflowPriority priority() { return priority; }

    /** Total number of blocks to process (0 if unknown). */
    public int totalBlocks() { return totalBlocks; }

    /** Number of blocks successfully processed so far. */
    public int completedBlocks() { return completedBlocks; }

    /** Number of blocks that failed to process. */
    public int failedBlocks() { return failedBlocks; }

    /** Item IDs that are needed but currently unavailable. */
    public List<String> missingItems() { return List.copyOf(missingItems); }

    /** Optional human-readable detail about the current workflow. */
    public String detailMessage() { return detailMessage; }

    /** {@code true} if this workflow is suspended (waiting for items). */
    public boolean suspended() { return suspended; }

    /** {@code true} if this workflow is paused by the user. */
    public boolean paused() { return paused; }

    /** Timestamp (millis) when this entry was created. */
    public long createdAt() { return createdAt; }

    /** Timestamp (millis) of the most recent state change. */
    public long lastUpdatedAt() { return lastUpdatedAt; }

    // ──────────────────────────────────────────────────────────────────
    //  Derived queries
    // ──────────────────────────────────────────────────────────────────

    /** Returns {@code true} if this entry represents a running (non-paused, non-suspended) workflow. */
    public boolean hasActiveWorkflow() {
        return type != null && !suspended && !paused;
    }

    /** Returns {@code true} if this entry occupies a slot (active or suspended). */
    public boolean isOccupied() {
        return type != null;
    }

    /** Returns the overall progress as a float in [0.0, 1.0]. Returns 0 when total is 0. */
    public float progress() {
        if (totalBlocks <= 0) return 0.0F;
        return Math.min(1.0F, (float) (completedBlocks + failedBlocks) / (float) totalBlocks);
    }

    /** Returns the number of remaining blocks, or 0 if total is 0 or all done. */
    public int remainingBlocks() {
        if (totalBlocks <= 0) return 0;
        return Math.max(0, totalBlocks - (completedBlocks + failedBlocks));
    }

    /** Returns {@code true} if all blocks have been processed. */
    public boolean isComplete() {
        return totalBlocks > 0 && (completedBlocks + failedBlocks) >= totalBlocks;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Snapshot
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates an immutable snapshot of this entry for network transmission
     * and UI consumption.
     */
    public RtsWorkflowStatus snapshot() {
        if (type == null) {
            return RtsWorkflowStatus.idle();
        }
        return RtsWorkflowStatus.fromRaw(
                type, priority, totalBlocks, completedBlocks, failedBlocks,
                List.copyOf(missingItems), detailMessage, suspended, paused, id);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Package-private mutators (only engine may call)
    // ──────────────────────────────────────────────────────────────────

    void setType(RtsWorkflowType type) {
        this.type = Objects.requireNonNull(type);
        touch();
    }

    public void setPriority(RtsWorkflowPriority priority) {
        this.priority = Objects.requireNonNull(priority);
        touch();
    }

    void setTotalBlocks(int totalBlocks) {
        this.totalBlocks = Math.max(0, totalBlocks);
        touch();
    }

    void addCompletedBlocks(int delta) {
        this.completedBlocks = Math.max(0, Math.min(this.totalBlocks, this.completedBlocks + Math.max(0, delta)));
        touch();
    }

    /** Sets the completed block count to an absolute value (used for world-scan refresh). */
    void setCompletedBlocks(int absoluteValue) {
        this.completedBlocks = Math.max(0, Math.min(this.totalBlocks, absoluteValue));
        touch();
    }

    void addFailedBlocks(int delta) {
        this.failedBlocks = Math.max(0, this.failedBlocks + delta);
        touch();
    }

    void addMissingItems(List<String> items) {
        if (items != null) {
            for (String item : items) {
                if (!missingItems.contains(item)) {
                    missingItems.add(item);
                }
            }
        }
        touch();
    }

    void clearMissingItems() {
        this.missingItems.clear();
        touch();
    }

    void setDetailMessage(String detailMessage) {
        this.detailMessage = detailMessage != null ? detailMessage : "";
        touch();
    }

    void setSuspended(boolean suspended) {
        this.suspended = suspended;
        touch();
    }

    void setPaused(boolean paused) {
        this.paused = paused;
        touch();
    }

    /** Resets this entry to its default (idle) state — used when recycling slots. */
    void reset() {
        this.type = null;
        this.priority = RtsWorkflowPriority.NORMAL;
        this.totalBlocks = 0;
        this.completedBlocks = 0;
        this.failedBlocks = 0;
        this.missingItems.clear();
        this.detailMessage = "";
        this.suspended = false;
        this.paused = false;
        touch();
    }

    /** Marks the entry as updated (refreshes the idle-timeout clock). */
    void touch() {
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Object
    // ──────────────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof RtsWorkflowEntry other)) return false;
        return this.id == other.id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "RtsWorkflowEntry{id=" + id + ", type=" + type
                + ", progress=" + completedBlocks + "/" + totalBlocks
                + (suspended ? ", SUSPENDED" : "")
                + (paused ? ", PAUSED" : "")
                + "}";
    }
}
