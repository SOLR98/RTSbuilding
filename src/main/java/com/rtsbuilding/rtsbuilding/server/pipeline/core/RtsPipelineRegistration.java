package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import com.rtsbuilding.rtsbuilding.progression.RtsFeature;
import com.rtsbuilding.rtsbuilding.server.pipeline.mining.*;
import com.rtsbuilding.rtsbuilding.server.pipeline.placement.PendingPlacementPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.placement.PlacementExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.sync.NetworkSyncPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.sync.UiRefreshPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolBorrowPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.ProgressionGatePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.SessionDimensionPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.SessionValidatePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowProgressPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowStartPipe;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowPriority;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;

/**
 * Registers all workflow pipelines at mod initialisation.
 *
 * <p>Each {@link RtsWorkflowType} gets its own ordered sequence of
 * {@link PipelinePipe} stages.  The pipeline definitions here represent the
 * canonical "what should happen" for each operation type, serving as the
 * single source of truth for workflow orchestration.</p>
 *
 * <p>Call {@link #registerAll()} during {@code FMLCommonSetupEvent} to
 * populate the {@link PipelineRegistry}.</p>
 *
 * <h3>Pipeline designs</h3>
 *
 * <pre>{@code
 * MINE_SINGLE:
 *   ProgressionGate(REMOTE_BREAK) → SessionValidate → SessionDimension →
 *   StopPrevious → WorkflowStart → ToolBorrow → MiningExecute → UiRefresh
 *   [then async: tickActiveMining → finalizeMiningOperation completes workflow,
 *    returns tool, and fires COMPLETED event (real completion, entry removed)]
 *
 * ULTIMINE / AREA_MINE / AREA_DESTROY:
 *   ProgressionGate → SessionValidate → SessionDimension → StopPrevious →
 *   ToolBorrow → WorkflowStart → UltimineExecute → WorkflowProgress →
 *   NetworkSync → UiRefresh
 *   [then tickable: UltimineTickPipe] // async per-tick monitoring
 *
 * PLACE_SINGLE / QUICK_BUILD:
 *   SessionValidate → WorkflowStart → PlacementExecute → UiRefresh
 *
 * PLACE_BATCH:
 *   SessionValidate → WorkflowStart → PlacementExecute → PendingPlacement → UiRefresh
 * }</pre>
 */
public final class RtsPipelineRegistration {

    private RtsPipelineRegistration() {
    }

    /**
     * Registers all workflow pipelines.  Safe to call multiple times
     * (subsequent calls are no-ops via {@link PipelineRegistry} checks).
     */
    public static void registerAll() {
        registerMineSingle();
        registerUltimine();
        registerAreaMine();
        registerAreaDestroy();
        registerPlaceSingle();
        registerPlaceBatch();
        registerQuickBuild();
        registerStopMining();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Mining pipelines
    // ──────────────────────────────────────────────────────────────────

    /**
     * MINE_SINGLE — single-block remote mining.
     *
     * <p>The canonical pipeline covers: feature gate → session →
     * dimension → stop previous → start workflow → borrow tool →
     * execute → refresh UI.
     *
     * <p>Workflow completion, tool return, and history recording happen
     * <b>asynchronously</b> after the block is actually broken:
     * {@link com.rtsbuilding.rtsbuilding.server.service.mining.RtsMiningStateMachine#tickActiveMining}
     * detects block-break completion and calls
     * which completes the workflow entry (firing a <b>real</b> COMPLETED event
     * on the engine event bus, entry removed), returns the borrowed tool, and records history.
     * This ensures the workflow lifecycle aligns with the actual mining
     * operation rather than the pipeline execution.</p>
     */
    private static void registerMineSingle() {
        PipelineRegistry.miningPipeline(RtsWorkflowType.MINE_SINGLE)
                .pipe(new ProgressionGatePipe(RtsFeature.REMOTE_BREAK))
                .pipe(new SessionValidatePipe())
                .pipe(new SessionDimensionPipe())
                .pipe(new StopPreviousPipe(false))
                .pipe(new WorkflowStartPipe(RtsWorkflowType.MINE_SINGLE, RtsWorkflowPriority.NORMAL))
                .pipe(new ToolBorrowPipe())
                .pipe(new MiningExecutePipe())
                .pipe(new UiRefreshPipe())
                .asyncCompletion()
                .register();
    }

    /**
     * ULTIMINE — connected-block batch mining (tick-driven lifecycle).
     *
     * <p>The sync phase covers: feature gate → session → dimension → stop
     * previous → borrow tool → start workflow → execute (target collection,
     * state setup, beginRemoteMining).  After sync succeeds, the
     * {@link UltimineTickPipe} runs per server tick, monitoring batch
     * progress and completing the workflow when all targets are processed.
     * The tickable pipe is automatically registered with the
     * {@link TickablePipelineRegistry} and cleaned up on completion.</p>
     */
    private static void registerUltimine() {
        PipelineRegistry.miningPipeline(RtsWorkflowType.ULTIMINE)
                .pipe(new ProgressionGatePipe(RtsFeature.ULTIMINE))
                .pipe(new SessionValidatePipe())
                .pipe(new SessionDimensionPipe())
                .pipe(new StopPreviousPipe(true))
                .pipe(new WorkflowStartPipe(RtsWorkflowType.ULTIMINE, RtsWorkflowPriority.HIGH))
                .pipe(new ToolBorrowPipe())
                .pipe(new UltimineExecutePipe(RtsWorkflowType.ULTIMINE))
                .pipe(new WorkflowProgressPipe(0))
                .pipe(new NetworkSyncPipe())
                .pipe(new UiRefreshPipe())
                .tickable(new UltimineTickPipe())
                .register();
    }

    /**
     * AREA_MINE — 3D-volume area mining (tick-driven lifecycle).
     *
     * <p>Same structure as ULTIMINE but with area-specific target scanning.
     * {@link com.rtsbuilding.rtsbuilding.server.service.mining.RtsUltimineProcessor}
     * uses {@code AreaOperationExecutor} for target scanning.</p>
     */
    private static void registerAreaMine() {
        PipelineRegistry.miningPipeline(RtsWorkflowType.AREA_MINE)
                .pipe(new ProgressionGatePipe(RtsFeature.ULTIMINE))
                .pipe(new SessionValidatePipe())
                .pipe(new SessionDimensionPipe())
                .pipe(new StopPreviousPipe(true))
                .pipe(new WorkflowStartPipe(RtsWorkflowType.AREA_MINE, RtsWorkflowPriority.HIGH))
                .pipe(new ToolBorrowPipe())
                .pipe(new UltimineExecutePipe(RtsWorkflowType.AREA_MINE))
                .pipe(new WorkflowProgressPipe(0))
                .pipe(new NetworkSyncPipe())
                .pipe(new UiRefreshPipe())
                .tickable(new UltimineTickPipe())
                .register();
    }

    /**
     * AREA_DESTROY — shape-destroy from Quick-Build preview (tick-driven).
     *
     * <p>Same structure as ULTIMINE but with explicit position lists and
     * area-destroy validation.</p>
     */
    private static void registerAreaDestroy() {
        PipelineRegistry.miningPipeline(RtsWorkflowType.AREA_DESTROY)
                .pipe(new ProgressionGatePipe(RtsFeature.AREA_DESTROY))
                .pipe(new SessionValidatePipe())
                .pipe(new SessionDimensionPipe())
                .pipe(new StopPreviousPipe(true))
                .pipe(new WorkflowStartPipe(RtsWorkflowType.AREA_DESTROY, RtsWorkflowPriority.HIGH))
                .pipe(new ToolBorrowPipe())
                .pipe(new UltimineExecutePipe(RtsWorkflowType.AREA_DESTROY))
                .pipe(new WorkflowProgressPipe(0))
                .pipe(new NetworkSyncPipe())
                .pipe(new UiRefreshPipe())
                .tickable(new UltimineTickPipe())
                .register();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Placement pipelines
    // ──────────────────────────────────────────────────────────────────

    /**
     * PLACE_SINGLE — single-block remote placement.
     *
     * <p>Resolves the session, enqueues the placement job into the batch
     * processing queue (handled by
     * {@link com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementBatch}),
     * and refreshes the UI.</p>
     */
    private static void registerPlaceSingle() {
        PipelineRegistry.placementPipeline(RtsWorkflowType.PLACE_SINGLE)
                .pipe(new SessionValidatePipe())
                .pipe(new WorkflowStartPipe(RtsWorkflowType.PLACE_SINGLE, RtsWorkflowPriority.NORMAL))
                .pipe(new PlacementExecutePipe())
                .pipe(new UiRefreshPipe())
                .asyncCompletion()
                .register();
    }

    /**
     * PLACE_BATCH — multi-block batch placement (interactive).
     *
     * <p>Same as PLACE_SINGLE but adds a {@link PendingPlacementPipe} to
     * attempt resuming suspended jobs after the new batch is enqueued.</p>
     */
    private static void registerPlaceBatch() {
        PipelineRegistry.placementPipeline(RtsWorkflowType.PLACE_BATCH)
                .pipe(new SessionValidatePipe())
                .pipe(new WorkflowStartPipe(RtsWorkflowType.PLACE_BATCH, RtsWorkflowPriority.NORMAL))
                .pipe(new PlacementExecutePipe())
                .pipe(new PendingPlacementPipe())
                .pipe(new UiRefreshPipe())
                .asyncCompletion()
                .register();
    }

    /**
     * QUICK_BUILD — pre-resolved state shape placement.
     *
     * <p>Same structure as PLACE_SINGLE but the
     * {@link com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementBatch}
     * uses the quick-build placement path (state-based plan instead of
     * interactive ray-trace).</p>
     */
    private static void registerQuickBuild() {
        PipelineRegistry.placementPipeline(RtsWorkflowType.QUICK_BUILD)
                .pipe(new SessionValidatePipe())
                .pipe(new WorkflowStartPipe(RtsWorkflowType.QUICK_BUILD, RtsWorkflowPriority.NORMAL))
                .pipe(new PlacementExecutePipe())
                .pipe(new UiRefreshPipe())
                .asyncCompletion()
                .register();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Standalone stop pipeline
    // ──────────────────────────────────────────────────────────────────

    /**
     * STOP_MINING — stop any active mining/ultimine operation.
     *
     * <p>Resolves the session, then stops mining activity.  Used when the
     * player explicitly clicks the stop button or disables RTS mode.</p>
     */
    private static void registerStopMining() {
        PipelineRegistry.register(RtsWorkflowType.STOP_MINING)
                .pipe(new SessionValidatePipe())
                .pipe(new StopMiningPipe())
                .register();
    }
}
