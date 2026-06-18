package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * A single active (ticking) pipeline instance wrapping a {@link PipelineContext}
 * and its {@link TickablePipe}.
 *
 * <p>Created by {@link TickablePipelineRegistry} after the synchronous phase
 * of a pipeline completes successfully.  Each server tick, the registry calls
 * {@link #tick()} on all active instances.  When the tickable pipe signals
 * completion ({@link TickResult.Done} or {@link TickResult.Error}), this
 * instance is marked as completed and removed from the registry.</p>
 *
 * <p>On failure ({@link TickResult.Error} or exception), the pipeline
 * automatically rolls back the associated workflow entry to prevent slot
 * leaks — mirroring the fail-fast rollback behaviour of
 * {@link WorkflowPipeline}.</p>
 *
 * <p>Instances are <b>not</b> thread-safe — they are designed for
 * single-threaded server tick usage.</p>
 */
public final class ActivePipeline {

    private final ServerPlayer player;
    private final PipelineContext ctx;
    private final TickablePipe pipe;
    private boolean completed;

    /**
     * @param player the server-side player
     * @param ctx    the pipeline context (includes shared data with entry ID)
     * @param pipe   the tickable pipe to invoke each tick
     */
    public ActivePipeline(ServerPlayer player, PipelineContext ctx, TickablePipe pipe) {
        this.player = player;
        this.ctx = ctx;
        this.pipe = pipe;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Accessors
    // ──────────────────────────────────────────────────────────────────

    /** Returns the server-side player. */
    public ServerPlayer player() {
        return player;
    }

    /** Returns the pipeline context. */
    public PipelineContext context() {
        return ctx;
    }

    /** Returns {@code true} if this pipeline has finished ticking. */
    public boolean isCompleted() {
        return completed;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Tick
    // ──────────────────────────────────────────────────────────────────

    /**
     * Invokes the tickable pipe once.  Call every server tick until this
     * method returns a non-empty result.
     *
     * <p>On failure, the workflow entry (if any) is automatically cancelled
     * to prevent slot leaks.</p>
     *
     * @return an empty {@link Optional} if the pipe is still working (call
     *         again next tick), or a {@link PipelineResult} if the pipe
     *         has finished (success or failure)
     */
    public Optional<PipelineResult> tick() {
        if (completed) {
            return Optional.empty();
        }
        try {
            TickResult result = pipe.tick(ctx);
            return switch (result) {
                case TickResult.Running r -> Optional.empty();
                case TickResult.Done d -> {
                    completed = true;
                    completeWorkflow();
                    yield Optional.of(PipelineResult.success());
                }
                case TickResult.Error e -> {
                    completed = true;
                    rollbackWorkflow();
                    RtsbuildingMod.LOGGER.warn("[ActivePipeline] Tickable pipe failed for player {}: {}",
                            player.getGameProfile().getName(), e.message());
                    yield Optional.of(PipelineResult.failure(e.message()));
                }
            };
        } catch (Exception e) {
            completed = true;
            rollbackWorkflow();
            RtsbuildingMod.LOGGER.error("[ActivePipeline] Tickable pipe threw for player {}",
                    player.getGameProfile().getName(), e);
            return Optional.of(PipelineResult.failure(
                    "Tickable pipe threw: " + e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Rollback
    // ──────────────────────────────────────────────────────────────────

    /**
     * Completes the workflow entry (if one was created) when the tickable
     * pipe signals normal completion.
     *
     * <p>This ensures the workflow entry is properly closed even in edge
     * cases where the business logic did not complete it (e.g. creative-mode
     * ultimine where targets are broken instantly without going through
     * {@code finalizeMiningOperation}).  Since {@code token.complete()} is
     * idempotent — it becomes a no-op if the entry was already removed —
     * this is safe to call even after business logic has already completed
     * the workflow.</p>
     */
    private void completeWorkflow() {
        if (!ctx.hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID)) {
            return;
        }
        int entryId = ctx.getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
        RtsWorkflowEngine.getInstance().from(player, entryId)
                .ifPresent(token -> {
                    token.complete();
                });
    }

    /**
     * Cancels the workflow entry (if one was created) to prevent slot leaks
     * when the tickable phase fails or throws.
     *
     * <p>Mirrors the fail-fast rollback in {@link WorkflowPipeline}.
     * Safe to call even when no entry ID is present — it becomes a no-op.</p>
     */
    private void rollbackWorkflow() {
        if (!ctx.hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID)) {
            return;
        }
        int entryId = ctx.getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
        RtsWorkflowEngine.getInstance().from(player, entryId)
                .ifPresent(token -> {
                    token.cancel();
                    RtsbuildingMod.LOGGER.info("[ActivePipeline] Rolled back workflow #{} for player {}",
                            entryId, player.getGameProfile().getName());
                });
    }
}
