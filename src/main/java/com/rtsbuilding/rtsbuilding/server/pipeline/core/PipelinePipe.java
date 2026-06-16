package com.rtsbuilding.rtsbuilding.server.pipeline.core;

/**
 * A single stage in a {@link WorkflowPipeline}.
 *
 * <p>Each pipe is an independent, composable unit that performs one specific
 * concern — validation, tool borrowing, workflow tracking, network sync, etc.
 * Pipes communicate via the mutable shared data in {@link PipelineContext}.</p>
 *
 * <p>This is a {@link FunctionalInterface} — implement with a lambda or method
 * reference for simple logic, or create a named class for complex pipes.</p>
 *
 * <h3>Execution contract</h3>
 * <ul>
 *   <li>Return {@link PipelineResult.Success} to continue to the next pipe.</li>
 *   <li>Return {@link PipelineResult.Failure} to stop the pipeline (fail-fast).
 *       The pipeline will not execute any further pipes.</li>
 *   <li>Return {@link PipelineResult.Skip} to skip all remaining pipes in
 *       the current execution.  This is <b>not</b> an error — it is an
 *       intentional early exit (e.g. creative mode bypassing tool handling).</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>{@code
 * // Lambda — simple validation
 * .pipe(ctx -> {
 *     if (!RtsProgressionManager.canUse(ctx.player(), RtsFeature.REMOTE_BREAK)) {
 *         return PipelineResult.failure("Feature not unlocked");
 *     }
 *     return PipelineResult.success();
 * })
 *
 * // Named class — complex logic
 * .pipe(new ToolBorrowPipe())
 * }</pre>
 */
@FunctionalInterface
public interface PipelinePipe<C extends PipelineContext> {

    /**
     * Executes this pipeline stage.
     *
     * @param ctx the pipeline context (player, session, args, shared data)
     * @return the result of this stage
     */
    PipelineResult execute(C ctx);
}
