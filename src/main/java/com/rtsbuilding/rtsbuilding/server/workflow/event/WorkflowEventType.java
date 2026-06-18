package com.rtsbuilding.rtsbuilding.server.workflow.event;

/**
 * Types of lifecycle events emitted by the workflow engine.
 *
 * <p>Subsystems (storage page refresh, history recording, sound effects) can
 * subscribe to these events instead of wiring explicit callbacks through
 * every code path.</p>
 */
public enum WorkflowEventType {
    /** A new workflow entry was created. */
    STARTED,
    /** Progress was updated (blocks completed / failed). */
    PROGRESS,
    /** A workflow was suspended (waiting for items). */
    SUSPENDED,
    /** A suspended workflow was resumed. */
    RESUMED,
    /** A workflow was completed successfully (entry is removed afterwards). */
    COMPLETED,

    /**
     * The sync phase of a pipeline completed successfully.
     *
     * <p>Unlike {@link #COMPLETED}, this event does <b>not</b> mean the
     * workflow itself is done — only that the synchronous setup phase of
     * a pipeline finished.  The workflow may continue asynchronously
     * (e.g. a placement batch job was enqueued and is still processing).
     * Entry is <b>not</b> removed when this event fires.</p>
     */
    SYNC_PHASE_COMPLETED,
    /** A workflow was cancelled by the user. */
    CANCELLED,
    /** A workflow was automatically cleaned up due to timeout. */
    TIMEOUT,
    /** A workflow was paused by the user. */
    PAUSED,
    /** A paused workflow was unpaused by the user. */
    UNPAUSED
}
