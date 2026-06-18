package com.rtsbuilding.rtsbuilding.server.pipeline.context;

import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.mining.MiningExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolBorrowPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.SessionValidatePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowStartPipe;
import com.rtsbuilding.rtsbuilding.server.service.mining.RtsToolLease;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Strongly-typed pipeline context for mining operations.
 *
 * <p>Provides type-safe accessors for mining-specific arguments and shared
 * data, eliminating {@code ctx.<BlockPos>getArg(ARG_POS)} casts throughout
 * mining pipe implementations.</p>
 *
 * <p>Pipes that are part of a mining pipeline (MINE_SINGLE, ULTIMINE,
 * AREA_MINE, AREA_DESTROY) should call {@link #require(PipelineContext)}
 * at the start of {@link PipelinePipe#execute(PipelineContext)}:</p>
 * <pre>{@code
 * MiningContext mctx = MiningContext.require(ctx);
 * BlockPos pos = mctx.getPos();
 * Direction face = mctx.getFace();
 * }</pre>
 */
public class MiningContext extends PipelineContext {

    /**
     * Creates a new mining pipeline context.
     *
     * @param player the server-side player executing the operation
     * @param args   immutable input arguments (a defensive copy is taken)
     */
    public MiningContext(ServerPlayer player, Map<String, Object> args) {
        super(player, args);
    }

    /**
     * Safely casts a {@link PipelineContext} to {@link MiningContext}.
     *
     * <p>Use this instead of a raw {@code (MiningContext) ctx} cast.  If the
     * context is not a {@code MiningContext}, an
     * {@link IllegalArgumentException} with a descriptive message is thrown,
     * making it far easier to diagnose misconfigured pipelines than a
     * bare {@link ClassCastException}.</p>
     *
     * @param ctx  the pipeline context to cast
     * @return the same context, typed as {@code MiningContext}
     * @throws IllegalArgumentException if {@code ctx} is not a
     *         {@code MiningContext} instance
     */
    public static MiningContext require(PipelineContext ctx) {
        if (ctx instanceof MiningContext mc) {
            return mc;
        }
        throw new IllegalArgumentException(
                "Expected MiningContext but got " + ctx.getClass().getSimpleName()
                + ". This pipe requires a mining pipeline (e.g. MINE_SINGLE, "
                + "ULTIMINE, AREA_MINE, AREA_DESTROY). "
                + "Did you register it in the wrong pipeline?");
    }

    // ──────────────────────────────────────────────────────────────
    //  Tool args
    // ──────────────────────────────────────────────────────────────

    /** Returns the hotbar slot index for the borrowed tool. */
    public int getToolSlot() {
        Integer val = getArg(ToolBorrowPipe.ARG_TOOL_SLOT);
        return val != null ? val : -1;
    }

    /** Returns the tool item ID (may be empty). */
    public String getToolItemId() {
        return getArg(ToolBorrowPipe.ARG_TOOL_ITEM_ID);
    }

    /** Returns the tool prototype stack. */
    public ItemStack getToolPrototype() {
        return getArg(ToolBorrowPipe.ARG_TOOL_PROTOTYPE);
    }

    // ──────────────────────────────────────────────────────────────
    //  Mining args
    // ──────────────────────────────────────────────────────────────

    /** Returns the target block position. */
    public BlockPos getPos() {
        return getArg(MiningExecutePipe.ARG_POS);
    }

    /**
     * Returns the mining face.
     *
     * @return the face direction, or {@code null} if not provided
     *         (defaults to {@link Direction#DOWN})
     */
    @Nullable
    public Direction getFace() {
        return getArg(MiningExecutePipe.ARG_FACE);
    }

    /**
     * Returns {@code true} if placed-block recovery is enabled.
     * Defaults to {@code false} if the argument is absent.
     */
    public boolean isAllowPlacedBlockRecovery() {
        return hasArg(MiningExecutePipe.ARG_ALLOW_PLACED_BLOCK_RECOVERY)
                && getArg(MiningExecutePipe.ARG_ALLOW_PLACED_BLOCK_RECOVERY);
    }

    /**
     * Returns {@code true} if tool protection is enabled.
     * Defaults to {@code true} if the argument is absent.
     */
    public boolean isToolProtectionEnabled() {
        return !hasArg(MiningExecutePipe.ARG_TOOL_PROTECTION_ENABLED)
                || getArg(MiningExecutePipe.ARG_TOOL_PROTECTION_ENABLED);
    }

    // ──────────────────────────────────────────────────────────────
    //  Shared data accessors
    // ──────────────────────────────────────────────────────────────

    /**
     * Returns the resolved storage session from {@link SessionValidatePipe},
     * or {@code null} if it has not been set yet.
     */
    @Nullable
    public RtsStorageSession getResolvedSession() {
        return getData(SessionValidatePipe.KEY_SESSION);
    }

    /**
     * Returns the borrowed tool lease from {@link ToolBorrowPipe},
     * or {@code null} if not set (creative-mode fast path).
     */
    @Nullable
    public RtsToolLease getToolLease() {
        return getData(ToolBorrowPipe.KEY_TOOL_LEASE);
    }

    /** Returns {@code true} if a tool lease is available in shared data. */
    public boolean hasToolLease() {
        return hasData(ToolBorrowPipe.KEY_TOOL_LEASE);
    }

    /**
     * Returns the workflow entry ID from {@link WorkflowStartPipe},
     * or {@code -1} if not set.
     */
    public int getWorkflowEntryId() {
        Integer val = getData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
        return val != null ? val : -1;
    }

    /** Returns {@code true} if a workflow entry ID is present in shared data. */
    public boolean hasWorkflowEntryId() {
        return hasData(PipelineContext.KEY_WORKFLOW_ENTRY_ID);
    }

    /**
     * Returns {@code true} if a specific tool was requested
     * (as opposed to free-form / any-tool mode).
     */
    public boolean isSelectedToolRequested() {
        Boolean val = getData(ToolBorrowPipe.KEY_SELECTED_TOOL_REQUESTED);
        return val != null && val;
    }
}
