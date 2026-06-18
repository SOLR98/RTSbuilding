package com.rtsbuilding.rtsbuilding.server.workflow.model;

import java.util.List;

/**
 * Immutable snapshot of the current workflow progress — the single unified
 * record for server-side queries, network transmission, and client-side UI.
 *
 * <p>This record merges the old {@code RtsWorkflowStatus} (raw fields +
 * computed methods) and {@code RtsWorkflowProgressData} (pre-computed fields
 * + UI helpers) into one.  Derived values ({@link #remainingBlocks()},
 * {@link #progress()}, {@link #isComplete()}) are pre-computed at snapshot
 * time so consumers never need to recalculate.</p>
 *
 * @param type            the type of workflow currently active
 * @param priority        the priority level of the active workflow
 * @param totalBlocks     total number of blocks to process (0 if unknown)
 * @param completedBlocks number of blocks successfully processed so far
 * @param failedBlocks    number of blocks that failed to process
 * @param remainingBlocks number of blocks still pending (pre-computed)
 * @param progress        progress as a float in [0.0, 1.0] (pre-computed)
 * @param suspended       {@code true} if this workflow is suspended (waiting for items)
 * @param paused          {@code true} if this workflow is paused by the user
 * @param isComplete      {@code true} if all blocks have been processed (pre-computed)
 * @param missingItems    item IDs that are needed but currently unavailable
 * @param detailMessage   optional human-readable detail about the current workflow
 * @param entryId         immutable workflow entry ID for linking with pending jobs
 */
public record RtsWorkflowStatus(
        RtsWorkflowType type,
        RtsWorkflowPriority priority,
        int totalBlocks,
        int completedBlocks,
        int failedBlocks,
        int remainingBlocks,
        float progress,
        boolean suspended,
        boolean paused,
        boolean isComplete,
        List<String> missingItems,
        String detailMessage,
        int entryId) {

    // ──────────────────────────────────────────────────────────────────
    //  Factories
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a status from raw (non-derived) values, pre-computing
     * {@code remainingBlocks}, {@code progress}, and {@code isComplete}.
     *
     * <p>Use this factory when constructing from network payloads or
     * mutable entry state.</p>
     */
    public static RtsWorkflowStatus fromRaw(
            RtsWorkflowType type, RtsWorkflowPriority priority,
            int totalBlocks, int completedBlocks, int failedBlocks,
            List<String> missingItems, String detailMessage,
            boolean suspended, boolean paused, int entryId) {
        int remaining = totalBlocks > 0
                ? Math.max(0, totalBlocks - (completedBlocks + failedBlocks))
                : 0;
        float progress = totalBlocks > 0
                ? Math.min(1.0F, (float) (completedBlocks + failedBlocks) / (float) totalBlocks)
                : 0.0F;
        boolean isComplete = totalBlocks > 0
                && (completedBlocks + failedBlocks) >= totalBlocks;
        return new RtsWorkflowStatus(type, priority, totalBlocks, completedBlocks,
                failedBlocks, remaining, progress, suspended, paused, isComplete,
                missingItems == null ? List.of() : List.copyOf(missingItems),
                detailMessage == null ? "" : detailMessage, entryId);
    }

    /**
     * Creates an empty (no active workflow) status.
     */
    public static RtsWorkflowStatus idle() {
        return new RtsWorkflowStatus(null, RtsWorkflowPriority.NORMAL,
                0, 0, 0, 0, 0.0F, false, false, false,
                List.of(), "", -1);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Convenience queries
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this represents an active (non-idle) workflow.
     */
    public boolean isActive() {
        return type != null;
    }

    /**
     * Returns {@code true} if this workflow has missing items that need
     * attention.
     */
    public boolean hasMissingItems() {
        return !missingItems.isEmpty();
    }

    /**
     * Returns {@code true} if this workflow has any failures.
     */
    public boolean hasFailures() {
        return failedBlocks > 0;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Display helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns a human-readable progress summary string,
     * e.g. {@code "45/100"} or {@code "0/0"}.
     */
    public String progressText() {
        return completedBlocks + "/" + (totalBlocks > 0 ? totalBlocks : 0);
    }

    /**
     * Returns the display label for the workflow type,
     * e.g. {@code "Mine"}, {@code "Ultimine"}.
     */
    public String typeLabel() {
        if (type == null) return "Idle";
        return switch (type) {
            case MINE_SINGLE  -> "Mine";
            case ULTIMINE     -> "Ultimine";
            case AREA_MINE    -> "Area Mine";
            case AREA_DESTROY -> "Destroy";
            case PLACE_SINGLE -> "Place";
            case PLACE_BATCH  -> "Place Batch";
            case QUICK_BUILD  -> "Quick Build";
            case STOP_MINING  -> "Stop Mining";
        };
    }
}
