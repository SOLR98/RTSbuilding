package com.rtsbuilding.rtsbuilding.server.pipeline.workflow;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineResult;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TypedKey;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowPriority;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;
import com.rtsbuilding.rtsbuilding.server.workflow.service.RtsWorkflowSlotManager;

/**
 * Starts a new workflow entry in the engine and stores the entry ID in
 * shared data for downstream pipes.
 *
 * <p>Expected context args:</p>
 * <ul>
 *   <li>{@code "workflowType"} — {@link RtsWorkflowType} (optional; defaults to the pipeline's type)</li>
 *   <li>{@code "workflowPriority"} — {@link RtsWorkflowPriority} (optional; defaults to NORMAL)</li>
 *   <li>{@code "totalBlocks"} — {@code int} total blocks to process (0 if unknown)</li>
 * </ul>
 *
 * <p>Stores the following in shared data:</p>
 * <ul>
 *   <li>{@code "workflowEntryId"} — {@code int} the immutable entry ID</li>
 * </ul>
 */
public final class WorkflowStartPipe implements PipelinePipe<PipelineContext> {

    public static final TypedKey<RtsWorkflowType> ARG_WORKFLOW_TYPE =
            new TypedKey<>("workflowType", RtsWorkflowType.class);
    public static final TypedKey<RtsWorkflowPriority> ARG_WORKFLOW_PRIORITY =
            new TypedKey<>("workflowPriority", RtsWorkflowPriority.class);
    public static final TypedKey<Integer> ARG_TOTAL_BLOCKS =
            new TypedKey<>("totalBlocks", Integer.class);

    public static final TypedKey<Integer> KEY_WORKFLOW_ENTRY_ID = PipelineContext.KEY_WORKFLOW_ENTRY_ID;

    private final RtsWorkflowType defaultType;
    private final RtsWorkflowPriority defaultPriority;

    /**
     * @param defaultType     the workflow type to use if not specified in args
     * @param defaultPriority the priority to use if not specified in args
     */
    public WorkflowStartPipe(RtsWorkflowType defaultType, RtsWorkflowPriority defaultPriority) {
        this.defaultType = defaultType;
        this.defaultPriority = defaultPriority;
    }

    @Override
    public PipelineResult execute(PipelineContext ctx) {
        RtsWorkflowType type = ctx.hasArg(ARG_WORKFLOW_TYPE)
                ? ctx.getArg(ARG_WORKFLOW_TYPE)
                : defaultType;

        RtsWorkflowPriority priority = ctx.hasArg(ARG_WORKFLOW_PRIORITY)
                ? ctx.getArg(ARG_WORKFLOW_PRIORITY)
                : defaultPriority;

        Integer totalBlocksArg = ctx.getArg(ARG_TOTAL_BLOCKS);
        int totalBlocks = totalBlocksArg != null ? totalBlocksArg : 0;

        var token = RtsWorkflowEngine.getInstance()
                .start(ctx.player(), type, priority, totalBlocks)
                .orElse(null);

        if (token == null) {
            RtsbuildingMod.LOGGER.warn("[WorkflowStartPipe] Workflow queue full for {}, type={}",
                    ctx.player().getGameProfile().getName(), type);
            return PipelineResult.failure("Workflow queue full (" + RtsWorkflowSlotManager.MAX_SLOTS + "/" + RtsWorkflowSlotManager.MAX_SLOTS + ")");
        }

        ctx.setData(KEY_WORKFLOW_ENTRY_ID, token.getEntryId());
        return PipelineResult.success();
    }
}
