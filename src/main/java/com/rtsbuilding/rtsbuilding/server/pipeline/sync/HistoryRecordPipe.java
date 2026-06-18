package com.rtsbuilding.rtsbuilding.server.pipeline.sync;

import com.rtsbuilding.rtsbuilding.server.history.HistoryBlockRecord;
import com.rtsbuilding.rtsbuilding.server.history.ServerHistoryManager;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineResult;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TypedKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

// ── TypedKey in same package, explicit import for incremental compiler ──


/**
 * Records a batch of block-break operations in the player's history.
 *
 * <p>Supports two mutually exclusive input modes:</p>
 * <ul>
 *   <li><b>Rich records</b> — {@link #ARG_HISTORY_RECORDS} as
 *       {@code List<HistoryBlockRecord>} (before-state captured by caller),
 *       delegates to {@link ServerHistoryManager#recordBreakWithRecords}.</li>
 *   <li><b>Simple positions</b> — {@link #ARG_HISTORY_POSITIONS} as
 *       {@code List<BlockPos>}, captures current state internally,
 *       delegates to {@link ServerHistoryManager#recordBreak}.</li>
 * </ul>
 *
 * <p>If both keys are absent, this pipe is a no-op.</p>
 */
public final class HistoryRecordPipe implements PipelinePipe<PipelineContext> {

    public static final TypedKey<List<BlockPos>> ARG_HISTORY_POSITIONS =
            new TypedKey<>("historyPositions", (Class) List.class);
    public static final TypedKey<List<HistoryBlockRecord>> ARG_HISTORY_RECORDS =
            new TypedKey<>("historyRecords", (Class) List.class);
    public static final TypedKey<Direction> ARG_HISTORY_FACE =
            new TypedKey<>("historyFace", Direction.class);

    @Override
    public PipelineResult execute(PipelineContext ctx) {
        boolean hasRecords = ctx.hasData(ARG_HISTORY_RECORDS);
        boolean hasPositions = ctx.hasData(ARG_HISTORY_POSITIONS);

        if (!hasRecords && !hasPositions) {
            return PipelineResult.success();
        }

        Direction face = ctx.hasData(ARG_HISTORY_FACE)
                ? ctx.getData(ARG_HISTORY_FACE)
                : Direction.DOWN;

        if (hasRecords) {
            List<HistoryBlockRecord> records = ctx.getData(ARG_HISTORY_RECORDS);
            if (!records.isEmpty()) {
                ServerHistoryManager.recordBreakWithRecords(ctx.player(), records, face);
            }
        } else {
            List<BlockPos> positions = ctx.getData(ARG_HISTORY_POSITIONS);
            if (!positions.isEmpty()) {
                ServerHistoryManager.recordBreak(ctx.player(), positions, face);
            }
        }

        return PipelineResult.success();
    }
}
