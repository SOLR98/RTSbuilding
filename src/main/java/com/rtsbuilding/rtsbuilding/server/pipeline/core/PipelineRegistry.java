package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.pipeline.context.MiningContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.context.PlaceContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.mining.MiningExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.mining.UltimineExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.placement.PlacementExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolBorrowPipe;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for all {@link WorkflowPipeline} instances.
 *
 * <p>Pipelines are registered once at mod initialisation and then looked up
 * by {@link RtsWorkflowType} at runtime.  The registry is a simple key-value
 * store backed by {@link ConcurrentHashMap}.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Registration (mod init):
 * PipelineRegistry.register(RtsWorkflowType.MINE_SINGLE)
 *     .pipe(new SessionValidatePipe())
 *     .pipe(new WorkflowStartPipe())
 *     ...;
 *
 * // Execution (at runtime):
 * PipelineRegistry.execute(RtsWorkflowType.MINE_SINGLE, ctx);
 * }</pre>
 */
public final class PipelineRegistry {

    private static final Map<RtsWorkflowType, WorkflowPipeline<?>> pipelines = new ConcurrentHashMap<>();

    private PipelineRegistry() {
    }

    // ──────────────────────────────────────────────────────────────────
    //  Registration
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates a new builder pipeline for the given workflow type,
     * expecting a standard {@link PipelineContext}.
     * The pipeline is <b>not</b> registered yet — call
     * {@link WorkflowPipeline#register()} after adding pipes to complete
     * registration.
     *
     * @param type the workflow type
     * @return a new, empty pipeline ready to be configured
     */
    public static WorkflowPipeline<PipelineContext> register(RtsWorkflowType type) {
        return new WorkflowPipeline<>(type);
    }

    /**
     * Creates a new builder pipeline for the given workflow type,
     * expecting a {@link MiningContext}.
     *
     * <p>Mining-specific pipes ({@link MiningExecutePipe},
     * {@link ToolBorrowPipe}, {@link UltimineExecutePipe}) declare their
     * context type as {@code PipelinePipe<MiningContext>}, so the compiler
     * will reject them if you try to add them to a pipeline built by
     * {@link #register(RtsWorkflowType)}.  Use this factory instead.</p>
     *
     * <p>Usage:</p>
     * <pre>{@code
     * PipelineRegistry.miningPipeline(RtsWorkflowType.MINE_SINGLE)
     *     .pipe(new MiningExecutePipe())    // ✓ PipelinePipe<MiningContext>
     *     .pipe(new SessionValidatePipe())  // ✓ PipelinePipe<PipelineContext> → ? super MiningContext
     *     .register();
     * }</pre>
     *
     * @param type the workflow type
     * @return a new, empty mining pipeline ready to be configured
     */
    public static WorkflowPipeline<MiningContext> miningPipeline(RtsWorkflowType type) {
        return new WorkflowPipeline<>(type);
    }

    /**
     * Creates a new builder pipeline for the given workflow type,
     * expecting a {@link PlaceContext}.
     *
     * <p>Placement-specific pipes ({@link PlacementExecutePipe}) declare
     * their context type as {@code PipelinePipe<PlaceContext>}, so the
     * compiler will reject them if you try to add them to a pipeline built
     * by {@link #register(RtsWorkflowType)}.  Use this factory instead.</p>
     *
     * <p>Usage:</p>
     * <pre>{@code
     * PipelineRegistry.placementPipeline(RtsWorkflowType.PLACE_SINGLE)
     *     .pipe(new PlacementExecutePipe())  // ✓ PipelinePipe<PlaceContext>
     *     .pipe(new SessionValidatePipe())   // ✓ PipelinePipe<PipelineContext> → ? super PlaceContext
     *     .register();
     * }</pre>
     *
     * @param type the workflow type
     * @return a new, empty placement pipeline ready to be configured
     */
    public static WorkflowPipeline<PlaceContext> placementPipeline(RtsWorkflowType type) {
        return new WorkflowPipeline<>(type);
    }

    /**
     * Registers a pre-built pipeline.  Used internally by
     * {@link WorkflowPipeline#register()}.
     */
    static void register(WorkflowPipeline<?> pipeline) {
        RtsWorkflowType type = pipeline.type();
        if (pipelines.putIfAbsent(type, pipeline) != null) {
            throw new IllegalArgumentException(
                    "Pipeline already registered for " + type);
        }
        RtsbuildingMod.LOGGER.info("[PipelineRegistry] Registered pipeline '{}' with {} pipe(s)",
                type, pipeline.pipes().size());
    }

    /**
     * Replaces an existing pipeline.  Useful for testing or dynamic reconfiguration.
     *
     * @param pipeline the new pipeline to replace with
     * @return the previous pipeline, or {@code null} if none was registered
     */
    @Nullable
    public static WorkflowPipeline<?> replace(WorkflowPipeline<?> pipeline) {
        return pipelines.put(pipeline.type(), pipeline);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Lookup
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the pipeline for the given workflow type.
     *
     * @param type the workflow type
     * @return the registered pipeline
     * @throws IllegalArgumentException if no pipeline is registered for this type
     */
    public static WorkflowPipeline<?> of(RtsWorkflowType type) {
        WorkflowPipeline<?> pipeline = pipelines.get(type);
        if (pipeline == null) {
            throw new IllegalArgumentException(
                    "No pipeline registered for " + type + ". " +
                    "Did you forget to call PipelineRegistry.register() during mod initialisation?");
        }
        return pipeline;
    }

    /**
     * Looks up and executes the pipeline for the given workflow type.
     *
     * @param type the workflow type
     * @param ctx  the pipeline context
     * @param <C>  the context type (inferred from the argument)
     * @return the pipeline execution result
     */
    @SuppressWarnings("unchecked")
    public static <C extends PipelineContext> PipelineResult execute(RtsWorkflowType type, C ctx) {
        WorkflowPipeline<C> pipeline = (WorkflowPipeline<C>) of(type);
        return pipeline.execute(ctx);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Admin
    // ──────────────────────────────────────────────────────────────────

    /**
     * Removes all registered pipelines.  Useful for testing or mod reload.
     */
    public static void clear() {
        pipelines.clear();
        RtsbuildingMod.LOGGER.info("[PipelineRegistry] All pipelines cleared");
    }

    /**
     * Returns the number of registered pipelines.
     */
    public static int size() {
        return pipelines.size();
    }
}
