package com.rtsbuilding.rtsbuilding.server.pipeline.workflow;

import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineResult;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TypedKey;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;

/**
 * Completes the workflow entry identified by the entry ID in shared data.
 *
 * <p>Reads from shared data:</p>
 * <ul>
 *   <li>{@code "workflowEntryId"} — {@code int} the entry ID to complete</li>
 * </ul>
 *
 * <p>This pipe is safe to include even when no workflow was started — it
 * simply skips if no entry ID is present.</p>
 */
public final class WorkflowCompletePipe implements PipelinePipe<PipelineContext> {

    public static final TypedKey<Integer> KEY_WORKFLOW_ENTRY_ID = PipelineContext.KEY_WORKFLOW_ENTRY_ID;

    @Override
    public PipelineResult execute(PipelineContext ctx) {
        if (!ctx.hasData(KEY_WORKFLOW_ENTRY_ID)) {
            return PipelineResult.success();
        }

        int entryId = ctx.getData(KEY_WORKFLOW_ENTRY_ID);
        var engine = RtsWorkflowEngine.getInstance();
        engine.from(ctx.player(), entryId).ifPresent(token -> {
            token.complete();
        });

        return PipelineResult.success();
    }
}
