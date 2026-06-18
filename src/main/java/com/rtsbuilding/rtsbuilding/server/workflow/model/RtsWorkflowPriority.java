package com.rtsbuilding.rtsbuilding.server.workflow.model;

/**
 * Priority levels for workflow operations.
 *
 * <p>Priority determines how the system should handle conflicts, resource
 * allocation, and UI emphasis when multiple workflows could be active or
 * when an operation needs to pre-empt another.</p>
 */
public enum RtsWorkflowPriority {

    /** Background / low-importance tasks (e.g. idle area-fill). */
    LOW(0),

    /** Default priority for most player-initiated operations. */
    NORMAL(1),

    /** Higher-priority tasks that should interrupt low-priority work. */
    HIGH(2),

    /** Critical tasks that must complete before anything else (e.g. tool-about-to-break). */
    CRITICAL(3);

    private final int rank;

    RtsWorkflowPriority(int rank) {
        this.rank = rank;
    }

    /**
     * Returns the numeric rank of this priority level.  Higher values
     * indicate greater urgency.
     */
    public int rank() {
        return this.rank;
    }

    /**
     * Returns {@code true} if this priority is strictly higher than the
     * given other priority.
     */
    public boolean isHigherThan(RtsWorkflowPriority other) {
        return this.rank > other.rank;
    }
}
