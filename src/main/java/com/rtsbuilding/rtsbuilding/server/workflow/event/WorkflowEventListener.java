package com.rtsbuilding.rtsbuilding.server.workflow.event;

import com.rtsbuilding.rtsbuilding.server.workflow.core.IWorkflowEngine;

/**
 * Functional interface for workflow lifecycle event listeners.
 *
 * <p>Register an instance via {@link IWorkflowEngine#addListener(WorkflowEventListener)}
 * to react to workflow state changes.  Implementations must be thread-safe
 * and should not block.</p>
 *
 * <pre>{@code
 * engine.addListener(event -> {
 *     if (event.type() == WorkflowEventType.COMPLETED) {
 *         // Refresh storage page
 *     }
 * });
 * }</pre>
 */
@FunctionalInterface
public interface WorkflowEventListener {
    void onEvent(WorkflowEvent event);
}
