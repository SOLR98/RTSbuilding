package com.rtsbuilding.rtsbuilding.server.workflow.model;

/**
 * Types of workflows that can be tracked by the workflow system.
 *
 * <p>Each enum constant represents a distinct category of remote operation:
 * single or batch, mining or placement.  This type is used to identify the
 * active workflow in the UI and to decide which progress/reporting format
 * to use.</p>
 */
public enum RtsWorkflowType {

    /** Single-block remote mining. */
    MINE_SINGLE,

    /** Connected-block (ultimine) batch mining. */
    ULTIMINE,

    /** Area-mine operation within a defined 3D volume. */
    AREA_MINE,

    /** Shape-destroy operation from Quick-Build preview. */
    AREA_DESTROY,

    /** Single-block remote placement. */
    PLACE_SINGLE,

    /** Multi-block batch placement (interactive per-position placement). */
    PLACE_BATCH,

    /** Quick-build (pre-resolved state) shape placement. */
    QUICK_BUILD,

    /**
     * Standalone stop-mining operation (no new mining started afterwards).
     *
     * <p>Used when the player explicitly cancels a mining operation or
     * disables RTS mode.  Unlike the implicit stop inside
     * {@code StopPreviousPipe}, this is a user-initiated stop.</p>
     */
    STOP_MINING
}
