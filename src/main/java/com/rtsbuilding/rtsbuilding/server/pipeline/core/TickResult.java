package com.rtsbuilding.rtsbuilding.server.pipeline.core;

import javax.annotation.Nullable;

/**
 * Sealed result type returned by every {@link TickablePipe#tick(PipelineContext)}.
 *
 * <p>Three variants exist:</p>
 * <ul>
 *   <li>{@link Running} — the pipe is still working; keep ticking.</li>
 *   <li>{@link Done} — the pipe finished normally; unregister and proceed.</li>
 *   <li>{@link Error} — the pipe failed; unregister and fail the pipeline.</li>
 * </ul>
 */
public sealed interface TickResult {

    /** The pipe is still working — call {@code tick()} again next frame. */
    record Running() implements TickResult {}

    /** The pipe finished its work normally. */
    record Done() implements TickResult {}

    /** The pipe encountered an error. */
    record Error(String message, @Nullable Throwable cause) implements TickResult {
        public Error(String message) {
            this(message, null);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Convenience factories
    // ──────────────────────────────────────────────────────────────────

    /** Shortcut for "still working". */
    static TickResult running() {
        return new Running();
    }

    /** Shortcut for "finished successfully". */
    static TickResult done() {
        return new Done();
    }

    /** Shortcut for "failed with a message". */
    static TickResult error(String message) {
        return new Error(message);
    }
}
