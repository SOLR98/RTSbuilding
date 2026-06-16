package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for active (ticking) pipeline instances.
 *
 * <p>After the synchronous phase of a {@link WorkflowPipeline} with tickable
 * pipes completes successfully, the pipeline execution is registered here.
 * The server tick loop calls {@link #tickAll()} to advance all active
 * pipelines by one tick.</p>
 *
 * <p>When a tickable pipe signals completion (normal or error), the active
 * pipeline is automatically removed.  Cleanup also happens on player logout.</p>
 *
 * <p>This is a singleton — obtain the instance via {@link #getInstance()}.</p>
 */
public final class TickablePipelineRegistry {

    private static final TickablePipelineRegistry INSTANCE = new TickablePipelineRegistry();

    /** Per-player list of active tickable pipelines. */
    private final Map<UUID, List<ActivePipeline>> activePipelines = new ConcurrentHashMap<>();

    private TickablePipelineRegistry() {
    }

    // ──────────────────────────────────────────────────────────────────
    //  Singleton
    // ──────────────────────────────────────────────────────────────────

    /** Returns the singleton registry instance. */
    public static TickablePipelineRegistry getInstance() {
        return INSTANCE;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Registration
    // ──────────────────────────────────────────────────────────────────

    /**
     * Registers a tickable pipe for per-tick execution.
     *
     * @param player the server-side player
     * @param ctx    the pipeline context (must contain workflow entry ID in shared data)
     * @param pipe   the tickable pipe to invoke each server tick
     */
    public static void register(ServerPlayer player, PipelineContext ctx, TickablePipe pipe) {
        INSTANCE.doRegister(player, ctx, pipe);
    }

    /**
     * Removes all active pipelines for a given player.  Called on player logout.
     *
     * @param playerId the player's UUID
     */
    public static void removeAll(UUID playerId) {
        INSTANCE.activePipelines.remove(playerId);
        RtsbuildingMod.LOGGER.debug("[TickablePipelineRegistry] Removed all pipelines for player {}", playerId);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Ticking
    // ──────────────────────────────────────────────────────────────────

    /**
     * Ticks all active pipeline instances once.  Completed/failed instances
     * are automatically removed.
     *
     * <p>Call this from the server tick event handler, after the mining state
     * machine has ticked.</p>
     */
    public static void tickAll() {
        INSTANCE.doTickAll();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internal
    // ──────────────────────────────────────────────────────────────────

    private void doRegister(ServerPlayer player, PipelineContext ctx, TickablePipe pipe) {
        List<ActivePipeline> list = activePipelines.computeIfAbsent(
                player.getUUID(), k -> new ArrayList<>());
        list.add(new ActivePipeline(player, ctx, pipe));
        RtsbuildingMod.LOGGER.debug("[TickablePipelineRegistry] Registered tickable pipeline for player {} (total: {})",
                player.getGameProfile().getName(), list.size());
    }

    private void doTickAll() {
        if (activePipelines.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, List<ActivePipeline>>> entryIt = activePipelines.entrySet().iterator();
        while (entryIt.hasNext()) {
            Map.Entry<UUID, List<ActivePipeline>> entry = entryIt.next();
            UUID playerId = entry.getKey();

            List<ActivePipeline> pipelines = entry.getValue();

            pipelines.removeIf(ap -> {
                // Per-entry pause valve: skip this pipeline if its workflow entry is paused
                if (ap.context().hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID)) {
                    int eid = ap.context().getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
                    if (RtsWorkflowEngine.getInstance().isEntryPaused(playerId, eid)) {
                        return false; // keep registered but skip tick
                    }
                }

                var result = ap.tick();
                if (result.isPresent()) {
                    RtsbuildingMod.LOGGER.debug("[TickablePipelineRegistry] Pipeline completed for player {}: {}",
                            ap.player().getGameProfile().getName(),
                            result.get() instanceof PipelineResult.Success ? "success" : "failure");
                    return true; // remove from list
                }
                return false;
            });

            if (pipelines.isEmpty()) {
                entryIt.remove();
            }
        }
    }
}
