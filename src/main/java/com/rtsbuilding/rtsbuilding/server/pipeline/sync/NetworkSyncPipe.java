package com.rtsbuilding.rtsbuilding.server.pipeline.sync;

import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineResult;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TypedKey;
import com.rtsbuilding.rtsbuilding.server.service.mining.RtsMiningNetworkHelper;

/**
 * Sends network progress updates to the client for mining operations.
 *
 * <p>Expected context args:</p>
 * <ul>
 *   <li>{@code "totalBlocks"} — {@code int} total blocks to process</li>
 *   <li>{@code "processedBlocks"} — {@code int} blocks processed so far (optional, default 0)</li>
 * </ul>
 */
public final class NetworkSyncPipe implements PipelinePipe<PipelineContext> {

    public static final TypedKey<Integer> ARG_TOTAL_BLOCKS =
            new TypedKey<>("totalBlocks", Integer.class);
    public static final TypedKey<Integer> ARG_PROCESSED_BLOCKS =
            new TypedKey<>("processedBlocks", Integer.class);

    @Override
    public PipelineResult execute(PipelineContext ctx) {
        int totalBlocks = ctx.hasData(ARG_TOTAL_BLOCKS)
                ? ctx.getData(ARG_TOTAL_BLOCKS)
                : 0;
        int processedBlocks = ctx.hasData(ARG_PROCESSED_BLOCKS)
                ? ctx.getData(ARG_PROCESSED_BLOCKS)
                : 0;

        RtsMiningNetworkHelper.sendUltimineProgress(
                ctx.player(), processedBlocks, totalBlocks);

        return PipelineResult.success();
    }
}
