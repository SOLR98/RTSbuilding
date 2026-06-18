package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

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

    /** Per-player, per-dimension list of active tickable pipelines. */
    private final Map<UUID, Map<ResourceKey<Level>, List<ActivePipeline>>> activePipelines = new ConcurrentHashMap<>();

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
     * Removes all active pipelines for a given player across all dimensions.
     * Called on player logout.
     *
     * @param playerId the player's UUID
     */
    public static void removeAll(UUID playerId) {
        INSTANCE.activePipelines.remove(playerId);
    }

    /**
     * Removes all active pipelines for a given player in a specific dimension.
     * Called when the player leaves a dimension.
     *
     * @param playerId  the player's UUID
     * @param dimension the dimension to clean up
     */
    public static void removeAll(UUID playerId, ResourceKey<Level> dimension) {
        Map<ResourceKey<Level>, List<ActivePipeline>> dimMap = INSTANCE.activePipelines.get(playerId);
        if (dimMap != null) {
            dimMap.remove(dimension);
            if (dimMap.isEmpty()) {
                INSTANCE.activePipelines.remove(playerId);
            }
        }
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
        ResourceKey<Level> dimension = player.level().dimension();
        Map<ResourceKey<Level>, List<ActivePipeline>> dimMap = activePipelines.computeIfAbsent(
                player.getUUID(), k -> new ConcurrentHashMap<>());
        List<ActivePipeline> list = dimMap.computeIfAbsent(dimension, k -> new ArrayList<>());
        list.add(new ActivePipeline(player, ctx, pipe));
    }

    private void doTickAll() {
        if (activePipelines.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, Map<ResourceKey<Level>, List<ActivePipeline>>>> playerIt =
                activePipelines.entrySet().iterator();

        while (playerIt.hasNext()) {
            Map.Entry<UUID, Map<ResourceKey<Level>, List<ActivePipeline>>> playerEntry = playerIt.next();
            UUID playerId = playerEntry.getKey();
            Map<ResourceKey<Level>, List<ActivePipeline>> dimPipelines = playerEntry.getValue();

            Iterator<Map.Entry<ResourceKey<Level>, List<ActivePipeline>>> dimIt =
                    dimPipelines.entrySet().iterator();

            while (dimIt.hasNext()) {
                Map.Entry<ResourceKey<Level>, List<ActivePipeline>> dimEntry = dimIt.next();
                ResourceKey<Level> pipelineDim = dimEntry.getKey();
                List<ActivePipeline> pipelines = dimEntry.getValue();

                // Only tick pipelines in the player's current dimension.
                // Pipelines from other dimensions remain registered but are
                // skipped — they will be ticked when the player returns, or
                // cleaned up by timeout / on dimension change.
                if (pipelines.isEmpty()) {
                    dimIt.remove();
                    continue;
                }

                // Check if any pipeline in this dimension list matches the
                // player's current dimension (all pipelines in the same dim
                // list share the same dimension key).
                ActivePipeline first = pipelines.get(0);
                ResourceKey<Level> playerCurrentDim = first.player().level().dimension();
                if (!pipelineDim.equals(playerCurrentDim)) {
                    continue; // skip this dimension entirely
                }

                pipelines.removeIf(ap -> {
                    // Per-entry pause valve: skip this pipeline if its workflow entry is paused
                    if (ap.context().hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID)) {
                        int eid = ap.context().getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
                        if (RtsWorkflowEngine.getInstance().isEntryPaused(playerId, pipelineDim, eid)) {
                            return false; // keep registered but skip tick
                        }
                    }

                    var result = ap.tick();
                    if (result.isPresent()) {
                        return true; // remove from list
                    }
                    return false;
                });

                if (pipelines.isEmpty()) {
                    dimIt.remove();
                }
            }

            if (dimPipelines.isEmpty()) {
                playerIt.remove();
            }
        }
    }
}
