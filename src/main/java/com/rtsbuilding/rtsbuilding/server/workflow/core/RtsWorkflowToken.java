package com.rtsbuilding.rtsbuilding.server.workflow.core;

import com.rtsbuilding.rtsbuilding.server.workflow.event.WorkflowEventType;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowStatus;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable token representing a single active workflow.
 *
 * <p>This is the <b>primary consumer-facing API</b> for interacting with the
 * workflow system.  Instead of manually juggling sessions, indices, and entry
 * IDs, callers obtain a token via one of the engine's factory methods and then
 * simply call lifecycle methods on it.</p>
 *
 * <p>The token internally holds the player's UUID and the immutable entry ID,
 * so it survives index shifts caused by earlier entries being removed.
 * All methods delegate to the engine that created this token.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Start a workflow and get a token
 * var token = engine.startMining(player, 100)
 *         .orElse(null);
 * if (token == null) {
 *     // Workflow queue was full
 *     return;
 * }
 *
 * // During processing
 * for (BlockPos pos : targets) {
 *     if (processBlock(pos)) {
 *         token.markProgress();
 *     } else {
 *         token.recordFailure();
 *     }
 * }
 *
 * // When done
 * token.complete();
 *
 * // From a different code location, reconstruct:
 * var token2 = engine.from(player, savedEntryId)
 *         .orElse(null);
 * if (token2 != null) {
 *     token2.markProgress();
 * }
 * }</pre>
 *
 * <p>Tokens are <b>not</b> thread-safe — they are designed for single-threaded
 * server tick usage.  Create a new token for each distinct workflow.</p>
 */
public final class RtsWorkflowToken {

    private final UUID playerId;
    private final int entryId;
    private final ResourceKey<Level> dimension;
    private final RtsWorkflowEngine engine;

    // ──────────────────────────────────────────────────────────────────
    //  Construction (package-private — only engine creates tokens)
    // ──────────────────────────────────────────────────────────────────

    RtsWorkflowToken(UUID playerId, int entryId, ResourceKey<Level> dimension, RtsWorkflowEngine engine) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.entryId = entryId;
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Identity
    // ──────────────────────────────────────────────────────────────────

    /** Returns the immutable entry ID for this workflow. */
    public int getEntryId() {
        return entryId;
    }

    /** Returns the UUID of the player who owns this workflow. */
    public UUID getPlayerId() {
        return playerId;
    }

    /** Returns the dimension this workflow belongs to. */
    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    /**
     * Returns {@code true} if this token still refers to a valid workflow
     * entry (i.e. it has not been completed, cancelled, or timed out).
     */
    public boolean isValid() {
        return resolveEntry() != null;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────────

    /**
     * Marks one unit of progress.
     * Equivalent to {@code updateProgress(1, null)}.
     */
    public void markProgress() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.addCompletedBlocks(1);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Updates progress by the given delta and optionally reports missing items.
     *
     * @param completedDelta number of units completed since last update
     * @param missingItems   (nullable) item IDs that were missing
     */
    public void updateProgress(int completedDelta, @Nullable List<String> missingItems) {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.addCompletedBlocks(completedDelta);
            entry.addMissingItems(missingItems);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Sets the completed block count to an absolute value (used for world-scan refresh).
     * <p>This is the only "set" method on the token; all other mutations are delta-based.
     * Use with caution — prefer {@link #updateProgress(int, List)} for normal progress.</p>
     */
    public void setCompletedBlocks(int absoluteValue) {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.setCompletedBlocks(absoluteValue);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Sets the total block count to an absolute value (used after target collection).
     * <p>Use this when the total number of blocks is only known after the execute
     * phase completes (e.g. ultimine/area-mine target scanning).</p>
     */
    public void setTotalBlocks(int totalBlocks) {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.setTotalBlocks(totalBlocks);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Records a single failure for this workflow.
     */
    public void recordFailure() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.addFailedBlocks(1);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Sets a human-readable detail message for this workflow.
     */
    public void setDetailMessage(String message) {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.setDetailMessage(message);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Suspends this workflow (marks it as waiting for items).
     */
    public void suspend() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.setSuspended(true);
            entry.setDetailMessage("等待物品...");
            engine.fireEvent(WorkflowEventType.SUSPENDED, playerId, entryId, entry);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Pauses this workflow (stops tick processing for this entry only).
     */
    public void pause() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            entry.setPaused(true);
            engine.fireEvent(WorkflowEventType.PAUSED, playerId, entryId, entry);
            engine.notifyPlayer(playerId, dimension);
        }
    }

    /**
     * Unpauses this workflow (resumes tick processing for this entry).
     *
     * @return {@code true} if the workflow was successfully unpaused
     */
    public boolean unpause() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null && entry.paused()) {
            entry.setPaused(false);
            engine.fireEvent(WorkflowEventType.UNPAUSED, playerId, entryId, entry);
            engine.notifyPlayer(playerId, dimension);
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if this workflow entry is paused.
     */
    public boolean isPaused() {
        RtsWorkflowEntry entry = resolveEntry();
        return entry != null && entry.paused();
    }

    /**
     * Resumes this workflow if it was suspended.
     *
     * @return {@code true} if the workflow was successfully resumed
     */
    public boolean resume() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null && entry.suspended()) {
            entry.setSuspended(false);
            entry.setDetailMessage("");
            engine.fireEvent(WorkflowEventType.RESUMED, playerId, entryId, entry);
            engine.notifyPlayer(playerId, dimension);
            return true;
        }
        return false;
    }

    /**
     * Completes this workflow — removes the entry and notifies the client.
     *
     * <p>Network notification is handled internally by
     * {@link RtsWorkflowEngine#removeEntry(UUID, ResourceKey, int)}, so there is no
     * need to call {@code engine.notifyPlayer()} here.</p>
     */
    public void complete() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            engine.fireEvent(WorkflowEventType.COMPLETED, playerId, entryId, entry);
            engine.removeEntry(playerId, dimension, entryId);
        }
    }

    /**
     * Cancels this workflow — removes the entry without recording it as
     * completed.
     *
     * <p>Network notification is handled internally by
     * {@link RtsWorkflowEngine#removeEntry(UUID, ResourceKey, int)}, so there is no
     * need to call {@code engine.notifyPlayer()} here.</p>
     */
    public void cancel() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry != null) {
            engine.fireEvent(WorkflowEventType.CANCELLED, playerId, entryId, entry);
            engine.removeEntry(playerId, dimension, entryId);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Queries
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns an immutable snapshot of the current progress for this workflow.
     *
     * @return the workflow status, or {@link RtsWorkflowStatus#idle()} if
     *         the entry no longer exists
     */
    public RtsWorkflowStatus getProgress() {
        RtsWorkflowEntry entry = resolveEntry();
        if (entry == null) {
            return RtsWorkflowStatus.idle();
        }
        return entry.snapshot();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internal
    // ──────────────────────────────────────────────────────────────────

    private RtsWorkflowEntry resolveEntry() {
        return engine.findEntry(playerId, dimension, entryId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof RtsWorkflowToken other)) return false;
        return this.playerId.equals(other.playerId) && this.entryId == other.entryId;
    }

    @Override
    public int hashCode() {
        return 31 * playerId.hashCode() + entryId;
    }

    @Override
    public String toString() {
        return "RtsWorkflowToken{player=" + playerId + ", entry=" + entryId + "}";
    }
}
