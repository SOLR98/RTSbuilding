package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.server.pipeline.context.MiningContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineRegistry;
import com.rtsbuilding.rtsbuilding.server.pipeline.mining.MiningExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.mining.UltimineExecutePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolBorrowPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowStartPipe;
import com.rtsbuilding.rtsbuilding.server.service.mining.RtsMiningStateMachine;
import com.rtsbuilding.rtsbuilding.server.service.mining.RtsMiningValidator;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowStatus;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 挖矿服务——管理单方块挖掘、连锁挖掘、范围挖掘和范围破坏。
 *
 * <p>职责范围：
 * <ul>
 *   <li>单方块挖掘</li>
 *   <li>连锁挖掘（Ultimine）</li>
 *   <li>范围挖掘（Area Mine）</li>
 *   <li>范围破坏（Area Destroy）</li>
 *   <li>临时主手持物品切换</li>
 * </ul>
 *
 * <p>从 Phase 3 开始，操作编排委托给 {@link PipelineRegistry}，
 * 此类仅负责参数转换和管道调度。</p>
 */
public final class RtsMiningService {

    private RtsMiningService() {
    }

    // =========================================================================
    //  Single-block mine
    // =========================================================================

    /**
     * 单方块挖掘——通过 MINE_SINGLE 管道执行远程挖掘。
     */
    public static void mine(ServerPlayer player, BlockPos pos, Direction face, boolean start, byte toolSlot,
            String toolItemId, ItemStack toolPrototype, boolean allowPlacedBlockRecovery,
            boolean toolProtectionEnabled) {
        if (start) {
            Map<String, Object> args = new HashMap<>();
            args.put(ToolBorrowPipe.ARG_TOOL_SLOT.name(), (int) toolSlot);
            args.put(ToolBorrowPipe.ARG_TOOL_ITEM_ID.name(), toolItemId);
            args.put(ToolBorrowPipe.ARG_TOOL_PROTOTYPE.name(), toolPrototype);
            args.put(MiningExecutePipe.ARG_POS.name(), pos);
            args.put(MiningExecutePipe.ARG_FACE.name(), face);
            args.put(MiningExecutePipe.ARG_ALLOW_PLACED_BLOCK_RECOVERY.name(), allowPlacedBlockRecovery);
            args.put(MiningExecutePipe.ARG_TOOL_PROTECTION_ENABLED.name(), toolProtectionEnabled);
            args.put(WorkflowStartPipe.ARG_TOTAL_BLOCKS.name(), 1);

            PipelineRegistry.execute(RtsWorkflowType.MINE_SINGLE,
                    new MiningContext(player, args));
            return;
        }

        // Stop — delegate to STOP_MINING pipeline (skipped if committed ultimine batch)
        RtsStorageSession session = RtsSessionService.getIfPresent(player);
        if (session != null && !RtsMiningValidator.isCommittedUltimineBatch(session)) {
            PipelineRegistry.execute(RtsWorkflowType.STOP_MINING,
                    new MiningContext(player, Map.of()));
        }
    }

    // =========================================================================
    //  Ultimine
    // =========================================================================

    /**
     * 连锁挖掘（Ultimine）——通过 ULTIMINE 管道执行。
     */
    public static void startUltimine(ServerPlayer player, BlockPos pos, Direction face, byte toolSlot, String toolItemId,
            ItemStack toolPrototype, int requestedLimit, byte mode, boolean toolProtectionEnabled) {
        Map<String, Object> args = new HashMap<>();
        args.put(ToolBorrowPipe.ARG_TOOL_SLOT.name(), (int) toolSlot);
        args.put(ToolBorrowPipe.ARG_TOOL_ITEM_ID.name(), toolItemId);
        args.put(ToolBorrowPipe.ARG_TOOL_PROTOTYPE.name(), toolPrototype);
        args.put(UltimineExecutePipe.ARG_POS.name(), pos);
        args.put(UltimineExecutePipe.ARG_FACE.name(), face);
        args.put(UltimineExecutePipe.ARG_REQUESTED_LIMIT.name(), requestedLimit);
        args.put(UltimineExecutePipe.ARG_MODE.name(), mode);
        args.put(UltimineExecutePipe.ARG_TOOL_PROTECTION_ENABLED.name(), toolProtectionEnabled);

        PipelineRegistry.execute(RtsWorkflowType.ULTIMINE,
                new MiningContext(player, args));
    }

    // =========================================================================
    //  Area Mine
    // =========================================================================

    /**
     * 范围挖掘（Area Mine）——通过 AREA_MINE 管道执行。
     */
    public static void areaMine(ServerPlayer player,
            int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
            byte toolSlot, String toolItemId, ItemStack toolPrototype,
            byte shapeType, byte fillType, boolean toolProtectionEnabled) {
        Map<String, Object> args = new HashMap<>();
        args.put(ToolBorrowPipe.ARG_TOOL_SLOT.name(), (int) toolSlot);
        args.put(ToolBorrowPipe.ARG_TOOL_ITEM_ID.name(), toolItemId);
        args.put(ToolBorrowPipe.ARG_TOOL_PROTOTYPE.name(), toolPrototype);
        args.put(UltimineExecutePipe.ARG_MIN_X.name(), minX);
        args.put(UltimineExecutePipe.ARG_MAX_X.name(), maxX);
        args.put(UltimineExecutePipe.ARG_MIN_Y.name(), minY);
        args.put(UltimineExecutePipe.ARG_MAX_Y.name(), maxY);
        args.put(UltimineExecutePipe.ARG_MIN_Z.name(), minZ);
        args.put(UltimineExecutePipe.ARG_MAX_Z.name(), maxZ);
        args.put(UltimineExecutePipe.ARG_SHAPE_TYPE.name(), shapeType);
        args.put(UltimineExecutePipe.ARG_FILL_TYPE.name(), fillType);
        args.put(UltimineExecutePipe.ARG_TOOL_PROTECTION_ENABLED.name(), toolProtectionEnabled);

        PipelineRegistry.execute(RtsWorkflowType.AREA_MINE,
                new MiningContext(player, args));
    }

    // =========================================================================
    //  Area Destroy
    // =========================================================================

    /**
     * 范围破坏（Area Destroy）——通过 AREA_DESTROY 管道执行。
     */
    public static void areaDestroy(ServerPlayer player, List<BlockPos> positions,
            byte toolSlot, String toolItemId, ItemStack toolPrototype, boolean toolProtectionEnabled) {
        Map<String, Object> args = new HashMap<>();
        args.put(ToolBorrowPipe.ARG_TOOL_SLOT.name(), (int) toolSlot);
        args.put(ToolBorrowPipe.ARG_TOOL_ITEM_ID.name(), toolItemId);
        args.put(ToolBorrowPipe.ARG_TOOL_PROTOTYPE.name(), toolPrototype);
        args.put(UltimineExecutePipe.ARG_POSITIONS.name(), positions);
        args.put(UltimineExecutePipe.ARG_TOOL_PROTECTION_ENABLED.name(), toolProtectionEnabled);

        PipelineRegistry.execute(RtsWorkflowType.AREA_DESTROY,
                new MiningContext(player, args));
    }

    // =========================================================================
    //  Area Destroy Progress Queries
    // =========================================================================

    /**
     * 获取当前范围破坏的总方块数。
     */
    public static int getAreaDestroyTotalBlocks(ServerPlayer player) {
        return RtsWorkflowEngine.getInstance().getAllProgress(player).stream()
                .filter(d -> d.type() == RtsWorkflowType.AREA_DESTROY)
                .findFirst()
                .map(RtsWorkflowStatus::totalBlocks)
                .orElse(0);
    }

    /**
     * 获取当前范围破坏的已破坏方块数量。
     */
    public static int getAreaDestroyCompletedBlocks(ServerPlayer player) {
        return RtsWorkflowEngine.getInstance().getAllProgress(player).stream()
                .filter(d -> d.type() == RtsWorkflowType.AREA_DESTROY)
                .findFirst()
                .map(RtsWorkflowStatus::completedBlocks)
                .orElse(0);
    }

    /**
     * 获取当前范围破坏的未破坏方块数。
     */
    public static int getAreaDestroyRemainingBlocks(ServerPlayer player) {
        return RtsWorkflowEngine.getInstance().getAllProgress(player).stream()
                .filter(d -> d.type() == RtsWorkflowType.AREA_DESTROY)
                .findFirst()
                .map(RtsWorkflowStatus::remainingBlocks)
                .orElse(0);
    }

    // =========================================================================
    //  Temporary context switcher (shared utility)
    // =========================================================================

    /**
     * 临时切换主手持物品执行操作。
     */
    public static <T> T withTemporaryMainHandItem(ServerPlayer player, ItemStack stack, Supplier<T> action) {
        return RtsMiningStateMachine.withTemporaryMainHandItem(player, stack, action);
    }
}
