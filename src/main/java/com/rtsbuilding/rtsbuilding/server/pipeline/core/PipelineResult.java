package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import javax.annotation.Nullable;

/**
 * Sealed result type returned by every {@link PipelinePipe} and
 * {@link WorkflowPipeline} execution.
 *
 * <p>Using a sealed interface guarantees exhaustive handling — all possible
 * outcomes are captured at compile time.</p>
 *
 * <p>Three variants exist:</p>
 * <ul>
 *   <li>{@link Success} — the pipe completed normally; continue to next pipe.</li>
 *   <li>{@link Failure} — the pipe failed; the pipeline stops (fail-fast).</li>
 *   <li>{@link Skip} — the pipe chose to skip; remaining pipes in this run are skipped.</li>
 * </ul>
 */
public sealed interface PipelineResult {

    /** The pipe completed normally. */
    record Success() implements PipelineResult {}

    /** The pipe failed.  Carries a human-readable message and an optional exception. */
    record Failure(String message, @Nullable Throwable cause) implements PipelineResult {
        public Failure(String message) {
            this(message, null);
        }
    }

    /**
     * The pipe chose to skip the remaining pipeline stages.
     * This is <b>not</b> an error — it is an intentional early-exit signal
     * (e.g. creative-mode mining bypasses tool-borrow and tick-based execution).
     */
    record Skip(String reason) implements PipelineResult {}

    /** Shared singleton for {@link Success} — stateless, so no need for new instances. */
    PipelineResult SUCCESS = new Success();

    // ──────────────────────────────────────────────────────────────────
    //  Convenience factories
    // ──────────────────────────────────────────────────────────────────

    /** Shortcut for a successful result. */
    static PipelineResult success() {
        return SUCCESS;
    }

    /** Shortcut for a failure result with a message. */
    static PipelineResult failure(String message) {
        return new Failure(message);
    }

    /** Shortcut for a skip result. */
    static PipelineResult skip(String reason) {
        return new Skip(reason);
    }
}
