package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolBorrowPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolReturnPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.SessionValidatePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowCompletePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowStartPipe;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.event.WorkflowEvent;
import com.rtsbuilding.rtsbuilding.server.workflow.event.WorkflowEventType;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered sequence of {@link PipelinePipe} stages, keyed by
 * {@link RtsWorkflowType}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Pipeline.of(WorkflowType.MINE_SINGLE)
 *     .pipe(new ProgressionGatePipe(RtsFeature.REMOTE_BREAK))
 *     .pipe(new SessionValidatePipe())
 *     .pipe(new WorkflowStartPipe())
 *     .pipe(new MiningExecutePipe())
 *     .pipe(new WorkflowCompletePipe())
 *     .pipe(new UiRefreshPipe())
 *     .register();
 * }</pre>
 *
 * <p>Execution follows a <b>fail-fast</b> strategy: if any pipe returns
 * {@link PipelineResult.Failure}, the pipeline stops immediately and the
 * remaining pipes are not executed.  {@link PipelineResult.Skip} also stops
 * the pipeline but is logged as a normal early exit, not an error.</p>
 */
public final class WorkflowPipeline<C extends PipelineContext> {

    private final RtsWorkflowType type;
    private final List<PipelinePipe<? super C>> pipes = new ArrayList<>();
    private final List<TickablePipe> tickablePipes = new ArrayList<>();
    private boolean asyncCompletion;

    /**
     * Package-private — use {@link PipelineRegistry#register(RtsWorkflowType)}.
     */
    WorkflowPipeline(RtsWorkflowType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Builder
    // ──────────────────────────────────────────────────────────────────

    /**
     * Appends a sync pipe to this pipeline.
     *
     * @param pipe the pipe to add (must not be null)
     * @return this pipeline instance (fluent)
     */
    public WorkflowPipeline<C> pipe(PipelinePipe<? super C> pipe) {
        pipes.add(Objects.requireNonNull(pipe, "pipe"));
        return this;
    }

    /**
     * Appends a tickable pipe to this pipeline.
     *
     * <p>Tickable pipes run <b>after</b> all sync pipes complete successfully.
     * They are invoked once per server tick until they signal completion or
     * failure.  Registration with {@link TickablePipelineRegistry} happens
     * automatically inside {@link #execute(PipelineContext)}.</p>
     *
     * @param pipe the tickable pipe to add (must not be null)
     * @return this pipeline instance (fluent)
     */
    public WorkflowPipeline<C> tickable(TickablePipe pipe) {
        tickablePipes.add(Objects.requireNonNull(pipe, "tickablePipe"));
        return this;
    }

    /**
     * Marks this pipeline as having asynchronous completion.
     *
     * <p>Pipelines whose actual operation completes <b>outside</b> the sync
     * phase (e.g. mining that waits for block-break ticks) should call this
     * to prevent the pipeline from firing a premature
     * {@link WorkflowEventType#SYNC_PHASE_COMPLETED} event at the end of the sync phase.
     * The COMPLETED event will instead be fired by the async completion path
     * (e.g. {@link WorkflowCompletePipe} inside
     * {@code finalizeMiningOperation}).</p>
     *
     * <p>Sync-only pipelines (e.g. placement) fire
     * {@link WorkflowEventType#SYNC_PHASE_COMPLETED} when all pipes succeed.
     * The actual {@link WorkflowEventType#COMPLETED} is fired later when
     * the async work finishes (e.g. placement batch processing).</p>
     *
     * @return this pipeline instance (fluent)
     */
    public WorkflowPipeline<C> asyncCompletion() {
        this.asyncCompletion = true;
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Execution — registered pipelines
    // ──────────────────────────────────────────────────────────────────

    /**
     * Executes all registered sync pipes in order.
     *
     * <p>Execution stops on the first {@link PipelineResult.Failure} or {@link PipelineResult.Skip} result.
     * On failure, remaining pipes are skipped and the failure is logged.</p>
     *
     * <p>If all sync pipes succeed and this pipeline has tickable pipes, they are
     * registered with the {@link TickablePipelineRegistry} for per-tick execution.
     * The pipeline result returned here is still {@link PipelineResult.Success};
     * the tickable phase runs asynchronously.</p>
     *
     * @param ctx the pipeline context (player, session, args)
     * @return the final result of the sync phase
     */
    public PipelineResult execute(C ctx) {
        Objects.requireNonNull(ctx, "ctx");

        for (int i = 0; i < pipes.size(); i++) {
            PipelinePipe<? super C> pipe = pipes.get(i);
            try {
                PipelineResult result = pipe.execute(ctx);
                ctx.setResult(result);

                switch (result) {
                    case PipelineResult.Success s -> {
                        // Continue to next pipe
                    }
                    case PipelineResult.Failure f -> {
                        RtsbuildingMod.LOGGER.warn("[Pipeline] Pipe[{}] '{}' failed: {}",
                                i, pipe.getClass().getSimpleName(), f.message());
                        rollbackIfNeeded(ctx);
                        firePipelineResultEvent(ctx, result);
                        return result;
                    }
                    case PipelineResult.Skip sk -> {
                        RtsbuildingMod.LOGGER.info("[Pipeline] Pipe[{}] '{}' skipped: {}",
                                i, pipe.getClass().getSimpleName(), sk.reason());
                        rollbackIfNeeded(ctx);
                        firePipelineResultEvent(ctx, result);
                        return result;
                    }
                }
            } catch (Exception e) {
                var failure = new PipelineResult.Failure(
                        "Pipe[" + i + "] '" + pipe.getClass().getSimpleName() + "' threw: " + e.getMessage(), e);
                ctx.setResult(failure);
                RtsbuildingMod.LOGGER.error("[Pipeline] Pipe[{}] '{}' threw", i,
                        pipe.getClass().getSimpleName(), e);
                rollbackIfNeeded(ctx);
                firePipelineResultEvent(ctx, failure);
                return failure;
            }
        }

        // Fire SYNC_PHASE_COMPLETED event for sync-only pipelines.
        // For async-completion pipelines (mining with tickable phase or
        // explicitly marked), the COMPLETED event is fired by the async
        // completion path (WorkflowCompletePipe in finalizeMiningOperation
        // or the tickable pipe). Firing SYNC_PHASE_COMPLETED here would
        // be misleading since the actual work is not done yet.
        if (tickablePipes.isEmpty() && !asyncCompletion) {
            firePipelineResultEvent(ctx, PipelineResult.success());
        }

        // If this pipeline has tickable pipes, register them for per-tick execution.
        // Before registering, strip transient sync-phase data from the context to
        // free memory (queue mode flags, intermediate results, etc.) — only core
        // data needed by the tickable phase and eventual cleanup is preserved.
        if (!tickablePipes.isEmpty()) {
            ctx.retainOnly(
                    PipelineContext.KEY_WORKFLOW_ENTRY_ID,
                    SessionValidatePipe.KEY_SESSION,
                    ToolBorrowPipe.KEY_TOOL_LEASE
            );
            TickablePipelineRegistry.register(ctx.player(), ctx, tickablePipes.get(0));
        }

        return PipelineResult.success();
    }

    /**
     * Registers this pipeline with the {@link PipelineRegistry}.
     *
     * @return this pipeline instance (fluent)
     */
    public WorkflowPipeline<C> register() {
        PipelineRegistry.register(this);
        return this;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Execution — ad-hoc cleanup sequences
    // ──────────────────────────────────────────────────────────────────

    /**
     * Executes an ad-hoc sequence of pipes with <b>best-effort</b> semantics.
     *
     * <p>Each pipe is tried independently; failures are logged but do not
     * prevent subsequent pipes from running.  This is designed for cleanup
     * and async completion paths (e.g. {@code finalizeMiningOperation})
     * where all steps should be attempted even if one fails.</p>
     *
     * <p>Uses the same rollback logic as {@link #execute(PipelineContext)}
     * when a pipe returns {@link PipelineResult.Failure} or throws.
     * {@link PipelineResult.Skip} is treated as a non-error and logged at
     * info level.</p>
     *
     * @param ctx   the pipeline context (player, args, shared data)
     * @param pipes the pipes to execute in order (best-effort)
     */
    @SafeVarargs
    public static void runCleanupSequence(PipelineContext ctx, PipelinePipe<? super PipelineContext>... pipes) {
        runCleanupSequence(ctx, List.of(pipes));
    }

    /**
     * Executes an ad-hoc sequence of pipes with best-effort semantics.
     *
     * @see #runCleanupSequence(PipelineContext, PipelinePipe[])
     */
    public static void runCleanupSequence(PipelineContext ctx, List<PipelinePipe<? super PipelineContext>> pipes) {
        Objects.requireNonNull(ctx, "ctx");
        for (int i = 0; i < pipes.size(); i++) {
            PipelinePipe<? super PipelineContext> pipe = pipes.get(i);
            try {
                PipelineResult result = pipe.execute(ctx);
                ctx.setResult(result);
                switch (result) {
                    case PipelineResult.Success s -> {}
                    case PipelineResult.Failure f -> {
                        RtsbuildingMod.LOGGER.error("[Pipeline] Cleanup pipe[{}] '{}' failed: {}",
                                i, pipe.getClass().getSimpleName(), f.message());
                        rollbackIfNeeded(ctx);
                    }
                    case PipelineResult.Skip sk -> {
                        RtsbuildingMod.LOGGER.info("[Pipeline] Cleanup pipe[{}] '{}' skipped: {}",
                                i, pipe.getClass().getSimpleName(), sk.reason());
                    }
                }
            } catch (Exception e) {
                RtsbuildingMod.LOGGER.error("[Pipeline] Cleanup pipe[{}] '{}' threw",
                        i, pipe.getClass().getSimpleName(), e);
                rollbackIfNeeded(ctx);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Accessors
    // ──────────────────────────────────────────────────────────────────

    /** Returns the workflow type this pipeline handles. */
    public RtsWorkflowType type() {
        return type;
    }

    /** Returns an unmodifiable view of the registered sync pipes. */
    public List<PipelinePipe<? super C>> pipes() {
        return Collections.unmodifiableList(pipes);
    }

    /**
     * Returns {@code true} if this pipeline has tickable (per-tick) pipes.
     */
    public boolean hasTickablePhase() {
        return !tickablePipes.isEmpty();
    }

    /**
     * Returns an unmodifiable view of the registered tickable pipes.
     */
    public List<TickablePipe> tickablePipes() {
        return Collections.unmodifiableList(tickablePipes);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Rolls back the workflow entry and borrowed tool if present.
     *
     * <p>Called when the pipeline exits early due to failure, skip, or
     * exception <b>after</b> {@link WorkflowStartPipe} has already created
     * a workflow entry and/or {@link ToolBorrowPipe} has borrowed a tool.
     * Without this rollback, the entry would remain in the slot manager
     * and/or the tool would remain borrowed indefinitely (resource leak).</p>
     *
     * <p>This is safe to call even when no entry or tool exists — each check
     * is gated by {@code hasData()}, making it a no-op if nothing was set.</p>
     */
    private static void rollbackIfNeeded(PipelineContext ctx) {
        // Rollback in reverse order of pipe execution:
        // tool lease first (non-critical, no side-effect if skipped),
        // then workflow entry (critical, removes slot).

        // 1. Return borrowed tool first (prevents tool leak)
        if (ctx.hasData(ToolBorrowPipe.KEY_TOOL_LEASE)) {
            try {
                new ToolReturnPipe().execute(ctx);
            } catch (Exception e) {
                RtsbuildingMod.LOGGER.error("[Pipeline] ToolReturnPipe rollback failed", e);
            }
        }
        // 2. Cancel workflow entry (prevents slot leak)
        if (ctx.hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID)) {
            int entryId = ctx.getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
            RtsWorkflowEngine.getInstance().from(ctx.player(), entryId)
                    .ifPresent(token -> token.cancel());
        }
    }

    /**
     * Fires a {@link WorkflowEvent} based on the pipeline sync-phase result.
     *
     * <p>If the context does not carry a workflow entry ID (no
     * {@link WorkflowStartPipe} was executed), this is a no-op.
     * Exceptions from the engine event bus are caught and logged so they
     * do not interfere with the pipeline caller.</p>
     *
     * @param ctx    the pipeline context (may carry workflowEntryId)
     * @param result the pipeline sync-phase result
     */
    private void firePipelineResultEvent(PipelineContext ctx, PipelineResult result) {
        if (!ctx.hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID)) {
            return;
        }
        int entryId = ctx.getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);

        WorkflowEventType eventType = switch (result) {
            case PipelineResult.Success s -> WorkflowEventType.SYNC_PHASE_COMPLETED;
            case PipelineResult.Failure f -> WorkflowEventType.CANCELLED;
            case PipelineResult.Skip sk -> WorkflowEventType.CANCELLED;
        };

        try {
            RtsWorkflowEngine.getInstance().firePipelineEvent(ctx.player(), entryId, eventType);
        } catch (Exception e) {
            RtsbuildingMod.LOGGER.error("[Pipeline] Failed to fire {} event for entry #{}: {}",
                    eventType, entryId, e.getMessage(), e);
        }
    }
}
