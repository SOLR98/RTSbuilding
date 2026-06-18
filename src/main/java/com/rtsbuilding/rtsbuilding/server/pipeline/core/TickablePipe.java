package com.rtsbuilding.rtsbuilding.server.pipeline.core;

/**
 * A pipeline pipe that executes across multiple server ticks.
 *
 * <p>Unlike a regular {@link PipelinePipe} which runs once synchronously,
 * a {@code TickablePipe} is invoked every server tick until it signals
 * completion.  This makes it ideal for operations whose lifecycle spans
 * several ticks — ultimine batch processing, area mining, etc.</p>
 *
 * <p>Implementations should be <b>stateless</b> where possible, storing
 * intermediate state in the shared data of {@link PipelineContext}.  If
 * instance state is needed, create a new instance per pipeline execution
 * (the {@link TickablePipelineRegistry} handles this).</p>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Return {@link TickResult#running()} to keep ticking next frame.</li>
 *   <li>Return {@link TickResult#done()} to signal normal completion.</li>
 *   <li>Return {@link TickResult#error(String)} to signal failure.</li>
 *   <li>Exceptions thrown by {@code tick()} are caught and treated as
 *       {@link TickResult.Error}.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * // Monitor ultimine batch progress
 * PipelineRegistry.register(RtsWorkflowType.ULTIMINE)
 *     .pipe(new ProgressionGatePipe(RtsFeature.ULTIMINE))
 *     .pipe(new SessionValidatePipe())
 *     // ... sync pipes ...
 *     .tickable(new UltimineTickPipe())
 *     .register();
 * }</pre>
 */
@FunctionalInterface
public interface TickablePipe {

    /**
     * Called every server tick while this pipe is registered in the
     * {@link TickablePipelineRegistry}.
     *
     * @param ctx the pipeline context (player, session, args, shared data)
     * @return the tick result — {@link TickResult#running()} to continue,
     *         {@link TickResult#done()} to finish, or
     *         {@link TickResult#error(String)} to fail
     */
    TickResult tick(PipelineContext ctx);
}
