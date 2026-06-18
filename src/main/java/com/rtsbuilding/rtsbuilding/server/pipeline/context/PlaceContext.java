package com.rtsbuilding.rtsbuilding.server.pipeline.context;

import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.placement.PlacementExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.SessionValidatePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowStartPipe;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed pipeline context for placement operations.
 *
 * <p>Provides type-safe accessors for placement-specific arguments and shared
 * data, eliminating {@code ctx.<BlockPos>getArg(ARG_POS)} casts throughout
 * placement pipe implementations.</p>
 *
 * <p>Pipes that are part of a placement pipeline (PLACE_SINGLE, PLACE_BATCH,
 * QUICK_BUILD) should call {@link #require(PipelineContext)} at the start of
 * {@link PipelinePipe#execute(PipelineContext)}:</p>
 * <pre>{@code
 * PlaceContext pctx = PlaceContext.require(ctx);
 * List<BlockPos> positions = pctx.getClickedPositions();
 * Direction face = pctx.getFace();
 * }</pre>
 */
public class PlaceContext extends PipelineContext {

    /**
     * Creates a new placement pipeline context.
     *
     * @param player the server-side player executing the operation
     * @param args   immutable input arguments (a defensive copy is taken)
     */
    public PlaceContext(ServerPlayer player, Map<String, Object> args) {
        super(player, args);
    }

    /**
     * Safely casts a {@link PipelineContext} to {@link PlaceContext}.
     *
     * <p>Use this instead of a raw {@code (PlaceContext) ctx} cast.  If the
     * context is not a {@code PlaceContext}, an
     * {@link IllegalArgumentException} with a descriptive message is thrown,
     * making it far easier to diagnose misconfigured pipelines than a
     * bare {@link ClassCastException}.</p>
     *
     * @param ctx  the pipeline context to cast
     * @return the same context, typed as {@code PlaceContext}
     * @throws IllegalArgumentException if {@code ctx} is not a
     *         {@code PlaceContext} instance
     */
    public static PlaceContext require(PipelineContext ctx) {
        if (ctx instanceof PlaceContext pc) {
            return pc;
        }
        throw new IllegalArgumentException(
                "Expected PlaceContext but got " + ctx.getClass().getSimpleName()
                + ". This pipe requires a placement pipeline (e.g. PLACE_SINGLE, "
                + "PLACE_BATCH, QUICK_BUILD). "
                + "Did you register it in the wrong pipeline?");
    }

    // ──────────────────────────────────────────────────────────────
    //  Placement args
    // ──────────────────────────────────────────────────────────────

    /** Returns the list of positions to place at. */
    public List<BlockPos> getClickedPositions() {
        return getArg(PlacementExecutePipe.ARG_CLICKED_POSITIONS);
    }

    /** Returns the placement face. */
    public Direction getFace() {
        return getArg(PlacementExecutePipe.ARG_FACE);
    }

    /** Returns the X hit offset. */
    public double getHitOffsetX() {
        return getArg(PlacementExecutePipe.ARG_HIT_OFFSET_X);
    }

    /** Returns the Y hit offset. */
    public double getHitOffsetY() {
        return getArg(PlacementExecutePipe.ARG_HIT_OFFSET_Y);
    }

    /** Returns the Z hit offset. */
    public double getHitOffsetZ() {
        return getArg(PlacementExecutePipe.ARG_HIT_OFFSET_Z);
    }

    /**
     * Returns the rotation steps.
     * Defaults to {@code 0} if the argument is absent.
     */
    public byte getRotateSteps() {
        Integer val = getArg(PlacementExecutePipe.ARG_ROTATE_STEPS);
        return val != null ? val.byteValue() : (byte) 0;
    }

    /**
     * Returns {@code true} if force-place is enabled.
     * Defaults to {@code false} if the argument is absent.
     */
    public boolean isForcePlace() {
        return args().containsKey(PlacementExecutePipe.ARG_FORCE_PLACE)
                && (Boolean) getArg(PlacementExecutePipe.ARG_FORCE_PLACE);
    }

    /**
     * Returns {@code true} if occupied positions should be skipped.
     * Defaults to {@code false} if the argument is absent.
     */
    public boolean isSkipIfOccupied() {
        return args().containsKey(PlacementExecutePipe.ARG_SKIP_IF_OCCUPIED)
                && (Boolean) getArg(PlacementExecutePipe.ARG_SKIP_IF_OCCUPIED);
    }

    /** Returns the item ID to place. */
    public String getItemId() {
        return getArg(PlacementExecutePipe.ARG_ITEM_ID);
    }

    /** Returns the item prototype stack. */
    public ItemStack getItemPrototype() {
        return getArg(PlacementExecutePipe.ARG_ITEM_PROTOTYPE);
    }

    /** Returns the ray origin X coordinate. */
    public double getRayOriginX() {
        return getArg(PlacementExecutePipe.ARG_RAY_ORIGIN_X);
    }

    /** Returns the ray origin Y coordinate. */
    public double getRayOriginY() {
        return getArg(PlacementExecutePipe.ARG_RAY_ORIGIN_Y);
    }

    /** Returns the ray origin Z coordinate. */
    public double getRayOriginZ() {
        return getArg(PlacementExecutePipe.ARG_RAY_ORIGIN_Z);
    }

    /** Returns the ray direction X component. */
    public double getRayDirX() {
        return getArg(PlacementExecutePipe.ARG_RAY_DIR_X);
    }

    /** Returns the ray direction Y component. */
    public double getRayDirY() {
        return getArg(PlacementExecutePipe.ARG_RAY_DIR_Y);
    }

    /** Returns the ray direction Z component. */
    public double getRayDirZ() {
        return getArg(PlacementExecutePipe.ARG_RAY_DIR_Z);
    }

    /**
     * Returns {@code true} if this is a quick-build placement.
     * Defaults to {@code false} if the argument is absent.
     */
    public boolean isQuickBuild() {
        return args().containsKey(PlacementExecutePipe.ARG_QUICK_BUILD)
                && (Boolean) getArg(PlacementExecutePipe.ARG_QUICK_BUILD);
    }

    /**
     * Returns {@code true} if empty-hand placement is forced.
     * Defaults to {@code false} if the argument is absent.
     */
    public boolean isForceEmptyHand() {
        return args().containsKey(PlacementExecutePipe.ARG_FORCE_EMPTY_HAND)
                && (Boolean) getArg(PlacementExecutePipe.ARG_FORCE_EMPTY_HAND);
    }

    /**
     * Returns {@code true} if the remote placement hint should be sent.
     * Defaults to {@code true} if the argument is absent.
     */
    public boolean isSendRemoteHint() {
        return !args().containsKey(PlacementExecutePipe.ARG_SEND_REMOTE_HINT)
                || (Boolean) getArg(PlacementExecutePipe.ARG_SEND_REMOTE_HINT);
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
}
