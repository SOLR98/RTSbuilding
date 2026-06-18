package com.rtsbuilding.rtsbuilding.server.workflow.event;

import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowStatus;

import java.util.UUID;

/**
 * An immutable event payload fired by the workflow engine.
 *
 * @param type     the type of event
 * @param playerId the UUID of the player who owns the workflow
 * @param entryId  the immutable entry ID of the affected workflow
 * @param status   a snapshot of the workflow state at the time of the event
 */
public record WorkflowEvent(
        WorkflowEventType type,
        UUID playerId,
        int entryId,
        RtsWorkflowStatus status) {
}
