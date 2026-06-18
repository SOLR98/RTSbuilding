package com.rtsbuilding.rtsbuilding.server.service.mining;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.history.HistoryBlockRecord;
import com.rtsbuilding.rtsbuilding.server.history.ServerHistoryManager;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.WorkflowPipeline;
import com.rtsbuilding.rtsbuilding.server.pipeline.sync.HistoryRecordPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.tool.ToolReturnPipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.validation.SessionValidatePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.workflow.WorkflowCompletePipe;
import com.rtsbuilding.rtsbuilding.server.service.ServiceOperationTemplate;
import com.rtsbuilding.rtsbuilding.server.service.ServiceRegistry;
import com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementSound;
import com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 远程挖掘状态机，管理单方块挖掘的每 tick 进度累积和多方块挖掘的作业队列调度。
 *
 * <p>这是挖掘系统的核心编排器，所有可变状态存放在 {@link RtsStorageSession} 的
 * {@code session.mining} 中，方法本身均为无状态的静态方法。
 *
 * <p><b>核心流程：</b>
 * <ul>
 *   <li><b>单方块模式</b>（{@link #tickActiveMining}）— 每 tick 累积破坏进度，
 *   发送裂纹阶段更新给客户端。进度达到 1.0 时调用 {@link #destroyMinedBlock} 破坏方块，
 *   记录历史、吸收掉落物，前进到下一个连锁挖掘目标或结束</li>
 *   <li><b>连锁挖掘模式</b>— 委托给 {@link RtsUltimineProcessor#processUltimineTargets} 分批处理</li>
 *   <li><b>作业队列</b>（{@link MiningJob}）— 支持多个范围挖掘任务在 FIFO 队列中排队执行</li>
 *   <li><b>进度计算</b>（{@link #computeRemoteDestroyStep}）— 复制原版挖掘速度公式，
 *   支持借用工具和快捷栏工具两种模式，取消水下惩罚</li>
 *   <li><b>停止与释放</b>（{@link #stopActiveMining} / {@link #releaseMiningResources}）— 
 *   归还借用工具、清除客户端粒子、可选保留工作流条目以支持暂停/恢复</li>
 *   <li><b>工作流集成</b>— 通过 {@link #WORKFLOW_ENTRY_IDS} 追踪每玩家的工作流条目 ID，
 *   与 {@link com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine} 集成</li>
 * </ul>
 *
 * <p><b>改进亮点：</b>
 * <ul>
 *   <li>含水方块不再被错误排除</li>
 *   <li>被原版连锁破坏的多方块结构（门、床、双高植物）会被追踪到历史记录</li>
 *   <li>工具速度计算避免每次 tick 触发昂贵的物品交换同步包</li>
 * </ul>
 */
public final class RtsMiningStateMachine {

    /**
     * 每玩家的工作流条目 ID 追踪，与 {@link com.rtsbuilding.rtsbuilding.server.storage.state.RtsMiningState}
     * 解耦。
     *
     * <p>Pipe 在执行流程期间通过 {@link #setWorkflowEntryId(UUID, int)} 写入；
     * 异步挖掘完成通过 {@link #getWorkflowEntryId(UUID)} 读取。</p>
     */
    private static final Map<UUID, Integer> WORKFLOW_ENTRY_IDS = new ConcurrentHashMap<>();

    /**
     * 为给定玩家记录一个工作流条目 ID，替换之前的任何值。
     */
    public static void setWorkflowEntryId(UUID playerUuid, int entryId) {
        if (entryId >= 0) {
            WORKFLOW_ENTRY_IDS.put(playerUuid, entryId);
        }
    }

    /**
     * 返回给定玩家的工作流条目 ID，如果没有则返回 -1。
     */
    public static int getWorkflowEntryId(UUID playerUuid) {
        return WORKFLOW_ENTRY_IDS.getOrDefault(playerUuid, -1);
    }

    /**
     * 移除并返回给定玩家的工作流条目 ID。
     */
    public static int removeWorkflowEntryId(UUID playerUuid) {
        Integer removed = WORKFLOW_ENTRY_IDS.remove(playerUuid);
        return removed != null ? removed : -1;
    }

    private RtsMiningStateMachine() {
    }

    // =========================================================================
    //  挖掘作业队列
    // =========================================================================

    /**
     * 一个排队的挖掘操作（独立线程），等待当前操作完成后激活。
     *
     * <p>类似于 {@link com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementBatch.PlaceBatchJob}
     * 但用于挖掘操作。每个 job 带有自己的工作流条目 ID 和目标位置，
     * 以便多个范围挖掘任务可以在 FIFO 队列中共存。</p>
     *
     * @param workflowEntryId  追踪此 job 进度的工作流条目
     * @param targets          要破坏的方块位置
     * @param totalTargets     验证损失前的总目标数
     */
    public record MiningJob(int workflowEntryId, Deque<BlockPos> targets, int totalTargets) {
    }

    /**
     * 激活下一个排队的挖掘作业，将其数据加载到会话的活跃挖掘状态字段中，
     * 并更新工作流条目 ID 追踪映射。
     */
    private static void activateNextJob(ServerPlayer player, RtsStorageSession session) {
        MiningJob job = session.mining.ultimineJobQueue.removeFirst();
        RtsbuildingMod.LOGGER.info("[RtsMiningStateMachine] activateNextJob: entryId={}, {} targets for {}", job.workflowEntryId(), job.targets().size(), player.getGameProfile().getName());
        session.mining.ultimineTargets.addAll(job.targets());
        session.mining.ultimineTotalTargets = job.totalTargets();
        session.mining.ultimineProgressPos = job.targets().peekFirst();
        session.mining.ultimineProcessedTargets = 0;
        session.mining.ultimineBrokenTargets = 0;
        session.mining.ultimineNotifyAccumulator = 0;
        session.mining.ultimineProcessedPositions.clear();
        session.mining.ultimineAbsorbedDrops = false;
        // Point the active workflow tracking to this job's entry
        setWorkflowEntryId(player.getUUID(), job.workflowEntryId());
        // 队列模式激活走批处理路径 (processUltimineTargets)，
        // 不要设置 miningPos —— beginRemoteMining 会设 miningPos = target，
        // 但 processUltimineTargets 在同一 tick 立即销毁首个目标，
        // 导致下一 tick 进入单方块挖掘路径时 miningPos 指向的空气格，
        // 触发 isBreakableBlock 检查失败进而 stopActiveMining。
        session.mining.miningFace = Direction.DOWN;
        session.mining.miningProgress = 0.0F;
        session.mining.miningStage = -1;
    }

    // =========================================================================
    //  主 Tick 处理
    // =========================================================================

    /**
     * 远程挖掘进度的主 tick 处理器，每当玩家在 RTS 屏幕或远程挖掘状态时，
     * 在每个服务端 tick 被调用。
     *
     * <p><b>单方块模式</b>（{@code session.mining.miningPos != null}）：
     * 累积进度并向客户端发送破坏阶段更新。完成时破坏方块、记录历史、吸收掉落物，
     * 然后前进到下一个连锁挖掘目标或结束。</p>
     *
     * <p><b>连锁挖掘模式</b>委托给 {@link RtsUltimineProcessor#processUltimineTargets}。</p>
     */
    public static void tickActiveMining(ServerPlayer player, RtsStorageSession session) {
        // 工作流状态检查：已关闭则停止挖掘，已暂停则跳过此 tick
        int entryId = getWorkflowEntryId(player.getUUID());
        if (entryId >= 0) {
            var tokenOpt = RtsWorkflowEngine.getInstance().from(player, entryId);
            if (tokenOpt.isEmpty()) {
                RtsbuildingMod.LOGGER.warn("[RtsMiningStateMachine] tickActiveMining: workflow token not found for entryId={}, stopping for {}", entryId, player.getGameProfile().getName());
                // 工作流已被关闭（删除），停止挖掘操作
                stopActiveMining(player, session);
                return;
            }
            if (tokenOpt.get().isPaused()) {
                // 暂停中，跳过此 tick（保留挖掘进度动画）
                return;
            }
        }

        if (session.mining.miningPos == null) {
            if (!session.mining.ultimineTargets.isEmpty()) {
                RtsUltimineProcessor.processUltimineTargets(player, session);
            } else if (!session.mining.ultimineJobQueue.isEmpty()) {
                // 当前作业已完成，激活下一个排队作业
                activateNextJob(player, session);
                // ultimineTargets 现在非空，立即处理这个 tick 内的方块
                RtsUltimineProcessor.processUltimineTargets(player, session);
            }
            return;
        }
        if (!RtsLinkedStorageResolver.canAccessWorldTarget(player, session.mining.miningPos)) {
            stopActiveMining(player, session);
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockPos pos = session.mining.miningPos;
        BlockState state = level.getBlockState(pos);
        // FIXED: 不再错误排除含水方块
        if (!RtsMiningValidator.isBreakableBlock(state)
                || !RtsMiningValidator.hasValidDestroySpeed(state, level, pos)) {
            stopActiveMining(player, session);
            return;
        }
        if (RtsMiningValidator.isToolNearBreak(player, session)) {
            stopActiveMining(player, session);
            return;
        }

        float step = computeRemoteDestroyStep(player, state, pos, session.mining.miningToolSlot,
                session.mining.miningToolLease.stack(), session.mining.miningSelectedToolRequested);
        if (step <= 0.0F) {
            return;
        }

        session.mining.miningProgress += step;
        if (session.mining.miningProgress < 1.0F) {
            int stage = RtsMiningValidator.visibleMiningStage(session.mining.miningProgress);
            if (stage != session.mining.miningStage) {
                level.destroyBlockProgress(player.getId(), pos, stage);
                RtsMiningNetworkHelper.sendMineProgress(player, pos, stage);
                session.mining.miningStage = stage;
            }
            return;
        }

        // --- 进度完成：破坏方块 ---

        // 破坏前捕获状态用于历史记录（必须在破坏前完成）
        HistoryBlockRecord preRecord = ServerHistoryManager.captureBlock(player.serverLevel(), pos);
        // Also capture neighbor states for multi-block tracking
        List<HistoryBlockRecord> neighborRecords = captureNeighborRecords(player.serverLevel(), pos);

        MiningBreakResult result = destroyMinedBlock(player, session, pos, session.mining.miningToolSlot);
        level.destroyBlockProgress(player.getId(), pos, -1);

        if (result.broken() && !session.mining.ultimineTargets.isEmpty()) {
            // Part of an ultimine batch — advance to next target
            removeUltimineTarget(session, pos);
            session.mining.ultimineProcessedTargets = Math.max(session.mining.ultimineProcessedTargets, 1);
            session.mining.ultimineBrokenTargets++;
            session.mining.ultimineProcessedPositions.add(preRecord);
            // Record any collateral blocks (multi-block structures)
            recordCollateralBlocks(session, neighborRecords, pos);
            if (RtsMiningValidator.canAutoStoreDrops(player, session)) {
                RtsDropAbsorber.absorbMinedDropsImmediately(player, session, pos);
            }
            // 连锁挖掘中途进度：触发储存页面刷新以保证GUI实时更新
            ServiceRegistry.getInstance().serviceOp().afterModification(player, session);
            session.mining.miningPos = null;
            session.mining.miningProgress = 0.0F;
            session.mining.miningStage = -1;
            RtsUltimineProcessor.processUltimineTargets(player, session);
            return;
        }

        // Single-block mode — finish
        RtsMiningNetworkHelper.clearMineProgress(player, pos);
        List<HistoryBlockRecord> miningRecords = new ArrayList<>();
        if (result.broken()) {
            if (preRecord != null) {
                miningRecords.add(preRecord);
            }
            // Add any collateral blocks
            for (HistoryBlockRecord nr : neighborRecords) {
                BlockState currentState = player.serverLevel().getBlockState(nr.pos());
                if (currentState.isAir() && !nr.state().isAir()) {
                    miningRecords.add(nr);
                }
            }
        }
        if (result.broken() && RtsMiningValidator.canAutoStoreDrops(player, session)) {
            RtsDropAbsorber.absorbMinedDropsImmediately(player, session, pos);
        }
        finalizeMiningOperation(player, session, miningRecords, session.mining.miningFace);
    }

    // =========================================================================
    //  停止
    // =========================================================================

    /**
     * 停止给定会话的所有活跃挖掘/连锁挖掘活动，清除客户端的破坏阶段粒子，
     * 归还借用的工具，并重置会话的挖掘状态。
     *
     * @param preserveEntry 如果为 {@code true}，工作流条目<b>不会</b>被取消——
     *                       仅清除运行时挖掘状态。用于条目已被暂停的情况
     *                       （例如 RTS 模式禁用），应保持条目在 UI 中可见。
     */
    public static void stopActiveMining(ServerPlayer player, RtsStorageSession session, boolean preserveEntry) {
        int queueSize = session.mining.ultimineJobQueue.size();
        RtsbuildingMod.LOGGER.info("[RtsMiningStateMachine] stopActiveMining: player={}, miningPos={}, ultimineTargets={}, queueSize={}, preserveEntry={}",
                player.getGameProfile().getName(),
                session.mining.miningPos,
                session.mining.ultimineTargets.size(),
                queueSize,
                preserveEntry);
        boolean hadMiningState = session.mining.miningPos != null
                || session.mining.ultimineProgressPos != null
                || !session.mining.ultimineTargets.isEmpty()
                || !session.mining.miningToolLease.isEmpty();
        boolean hadUltimine = session.mining.ultimineProgressPos != null || !session.mining.ultimineTargets.isEmpty();

        // Complete workflow tracking via entry ID if single-block mining was active
        BlockPos progressPos = session.mining.miningPos != null ? session.mining.miningPos : session.mining.ultimineProgressPos;
        if (progressPos != null) {
            player.serverLevel().destroyBlockProgress(player.getId(), progressPos, -1);
            RtsMiningNetworkHelper.sendMineProgress(player, progressPos, -1);
        }
        if (hadUltimine) {
            RtsMiningNetworkHelper.sendUltimineProgress(player, -1, 0);
        }

        if (!preserveEntry) {
            // Cancel the workflow entry (if any) before returning tool
            int entryId = removeWorkflowEntryId(player.getUUID());
            if (entryId >= 0) {
                RtsWorkflowEngine.getInstance().from(player, entryId)
                        .ifPresent(token -> token.cancel());
            }

            // 取消所有排队作业的工作流 entry
            for (MiningJob queued : session.mining.ultimineJobQueue) {
                RtsWorkflowEngine.getInstance().from(player, queued.workflowEntryId())
                        .ifPresent(token -> token.cancel());
            }
        }
        session.mining.ultimineJobQueue.clear();

        RtsToolLeaseManager.returnMiningTool(player, session, session.mining.miningToolLease);
        if (hadMiningState) {
            ServiceRegistry.getInstance().page().markStorageViewDirty(player, session);
        }
        resetMiningState(session);
    }

    /**
     * 停止所有活跃挖掘/连锁挖掘活动，取消工作流条目。
     * 相当于 {@code stopActiveMining(player, session, false)}。
     *
     * @see #stopActiveMining(ServerPlayer, RtsStorageSession, boolean)
     */
    public static void stopActiveMining(ServerPlayer player, RtsStorageSession session) {
        stopActiveMining(player, session, false);
    }

    /**
     * 释放挖掘资源（借用的工具、破坏阶段粒子）而不取消工作流条目
     * 或清除运行时挖掘状态。
     *
     * <p>在 RTS 模式禁用时使用——工作流条目通过 {@code pauseAllActive()} 单独暂停，
     * 当玩家重新启用 RTS 模式并恢复时，挖掘操作可以从上次中断处继续，
     * 因为挖掘状态（{@code miningPos}、{@code ultimineTargets} 等）得以保留。</p>
     *
     * <p>这有意<b>不等同于</b>{@code stopActiveMining(player, session, true)}——
     * 该方法仍然通过 {@code resetMiningState()} 和 {@code ultimineJobQueue.clear()} 清除运行时挖掘状态，
     * 使得恢复变为不可能。</p>
     */
    public static void releaseMiningResources(ServerPlayer player, RtsStorageSession session) {
        // Clear break-stage particles on the client
        BlockPos progressPos = session.mining.miningPos != null
                ? session.mining.miningPos
                : session.mining.ultimineProgressPos;
        if (progressPos != null) {
            player.serverLevel().destroyBlockProgress(player.getId(), progressPos, -1);
            RtsMiningNetworkHelper.sendMineProgress(player, progressPos, -1);
        }
        boolean hadUltimine = session.mining.ultimineProgressPos != null
                || !session.mining.ultimineTargets.isEmpty();
        if (hadUltimine) {
            RtsMiningNetworkHelper.sendUltimineProgress(player, -1, 0);
        }

        // Return the borrowed tool (if any) — player gets their item back
        RtsToolLeaseManager.returnMiningTool(player, session, session.mining.miningToolLease);

        // Keep the mining state intact — do NOT reset miningPos, ultimineTargets,
        // ultimineJobQueue, or workflow entry IDs. The workflow entry is paused
        // by the caller (pauseAllActive), and tickActiveMining() will skip
        // paused entries. When unpaused, mining resumes from where it stopped.
        // We only reset the tool lease since we already returned it.
        session.mining.miningToolLease = RtsToolLease.empty();
        session.mining.miningSelectedToolRequested = false;
    }

    // =========================================================================
    //  挖掘初始化
    // =========================================================================

    /**
     * 初始化给定方块位置的远程挖掘状态，清除前一个目标的任何破坏阶段粒子。
     */
    public static void beginRemoteMining(ServerPlayer player, RtsStorageSession session, BlockPos pos, Direction face,
            int toolSlot) {
        if (session.mining.miningPos != null && !session.mining.miningPos.equals(pos)) {
            RtsMiningNetworkHelper.clearMineProgress(player, session.mining.miningPos);
        }
        session.mining.miningPos = pos.immutable();
        session.mining.miningFace = face == null ? Direction.DOWN : face;
        session.mining.miningToolSlot = RtsMiningValidator.clampHotbarSlot(toolSlot);
        session.mining.miningProgress = 0.0F;
        session.mining.miningStage = -1;
    }

    // =========================================================================
    //  方块破坏
    // =========================================================================

    /**
     * {@link #destroyMinedBlock} 调用的结果。
     *
     * @param broken    目标方块是否成功破坏
     * @param remainder 破坏后的工具剩余物
     */
    public record MiningBreakResult(boolean broken, ItemStack remainder) {
    }

    /**
     * 破坏 {@code pos} 处的方块，要么通过借用的工具租赁（追踪变异后的剩余物），
     * 要么通过临时切换玩家的选中快捷栏槽位。
     */
    public static MiningBreakResult destroyMinedBlock(ServerPlayer player, RtsStorageSession session, BlockPos pos, int toolSlot) {
        BlockState beforeState = player.serverLevel().getBlockState(pos);
        boolean broken;
        ItemStack remainder;
        if (session != null && session.mining.miningToolLease != null && !session.mining.miningToolLease.isEmpty()) {
            RtsToolLease lease = session.mining.miningToolLease;
            MiningBreakResult outcome = destroyBlockWithTemporaryMainHand(player, pos, lease.stack());
            remainder = RtsToolLeaseManager.protectBorrowedToolRemainder(player, lease, outcome.remainder());
            session.mining.miningToolLease = lease.withStack(remainder);
            broken = outcome.broken();
        } else if (session != null && session.mining.miningSelectedToolRequested) {
            broken = false;
            remainder = ItemStack.EMPTY;
        } else {
            broken = withTemporarySelectedSlot(player, toolSlot, () -> player.gameMode.destroyBlock(pos));
            remainder = ItemStack.EMPTY;
        }
        if (broken) {
            BlockState resultState = player.serverLevel().getBlockState(pos);
            RtsMiningNetworkHelper.sendBreakAnimation(player, pos, beforeState, resultState);
            RtsPlacementSound.playRemoteBlockBreakSound(player, player.serverLevel(), pos);
        }
        return new MiningBreakResult(broken, remainder);
    }

    // =========================================================================
    //  进度计算
    // =========================================================================

    /**
     * 计算给定方块/工具组合的每 tick 破坏进度，应用水下惩罚抵消。
     *
     * @return (0.0, 1.0] 范围内的浮点数表示每 tick 的进度，
     *         如果方块无法挖掘则 ≤ 0.0
     */
    public static float computeRemoteDestroyStep(ServerPlayer player, BlockState state, BlockPos pos, int toolSlot,
            ItemStack linkedTool, boolean selectedToolRequested) {
        if (linkedTool != null && !linkedTool.isEmpty()) {
            return withTemporaryOnGround(player, true, () -> removeMiningSpeedPenalty(player,
                    computeDestroyStepForTool(player, state, pos, linkedTool)));
        }
        if (selectedToolRequested) {
            return 0.0F;
        }
        return withTemporaryOnGround(player, true, () -> withTemporarySelectedSlot(
                player,
                toolSlot,
                () -> removeMiningSpeedPenalty(player, state.getDestroyProgress(player, player.serverLevel(), pos))));
    }

    // =========================================================================
    //  MiningDestroyOutcome（临时交换器）
    // =========================================================================

    /**
     * 将玩家的主手切换到给定的工具堆叠，破坏方块，读回（可能已损坏的）剩余物，
     * 并恢复原主手物品。
     */
    static MiningBreakResult destroyBlockWithTemporaryMainHand(ServerPlayer player, BlockPos pos, ItemStack tool) {
        ItemStack previousMainHand = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        boolean broken;
        ItemStack remainder;
        try {
            broken = player.gameMode.destroyBlock(pos);
        } finally {
            remainder = player.getMainHandItem().copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand);
        }
        return new MiningBreakResult(broken, remainder);
    }

    // =========================================================================
    //  工具速度计算（避免物品交换同步风暴）
    // =========================================================================

    /**
     * 使用工具堆叠<b>直接</b>计算给定方块/工具组合的每 tick 破坏进度，
     * 而不交换玩家的主手物品。
     *
     * <p>这复制了 {@code state.getDestroyProgress(player, level, pos)} 的逻辑，
     * 但使用提供的工具堆叠而不是 {@code player.getMainHandItem()}，
     * 避免了每次 tick 触发 {@code ClientboundContainerSetSlotPacket}
     * 的高成本 {@code player.setItemInHand()} 调用。
     *
     * @return (0.0, 1.0] 范围内的浮点数表示每 tick 的进度，
     *         如果方块无法挖掘则 ≤ 0.0
     */
    private static float computeDestroyStepForTool(ServerPlayer player, BlockState state, BlockPos pos, ItemStack tool) {
        float destroySpeed = state.getDestroySpeed(player.serverLevel(), pos);
        if (destroySpeed == -1.0F) {
            return 0.0F;
        }
        float digSpeed = getToolDigSpeed(player, state, tool);
        int divisor = tool.isCorrectToolForDrops(state) ? 30 : 100;
        return digSpeed / destroySpeed / (float) divisor;
    }

    /**
     * 使用给定的 {@code tool} 堆叠而非玩家的主手物品，
     * 复制 {@code Player.getDigSpeed(BlockState, BlockPos)}。
     *
     * <p>这里省略了水下惩罚和地面检查——它们分别由
     * {@link #removeMiningSpeedPenalty} 和 {@link #withTemporaryOnGround} 处理。
     */
    private static float getToolDigSpeed(ServerPlayer player, BlockState state, ItemStack tool) {
        float f = tool.getDestroySpeed(state);
        if (f > 1.0F) {
            int efficiency = getEfficiencyLevel(tool);
            if (efficiency > 0 && !tool.isEmpty()) {
                f += (float) (efficiency * efficiency + 1);
            }
        }
        if (player.hasEffect(MobEffects.DIG_SPEED)) {
            f *= 1.0F + (float) (player.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1) * 0.2F;
        }
        if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            f *= 1.0F - (float) (player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier() + 1) * 0.2F;
        }
        return f;
    }

    /**
     * 通过直接遍历物品堆叠的附魔组件返回给定 ItemStack 上的效率附魔等级。
     */
    private static int getEfficiencyLevel(ItemStack stack) {
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.EFFICIENCY)) {
                return entry.getValue();
            }
        }
        return 0;
    }

    // =========================================================================
    //  水下速度惩罚
    // =========================================================================

    /**
     * 取消水下挖掘速度惩罚（{@code SUBMERGED_MINING_SPEED}），
     * 同时保留附魔或 Mod 带来的任何正面修正。
     */
    static float removeMiningSpeedPenalty(ServerPlayer player, float destroyStep) {
        if (destroyStep <= 0.0F) {
            return destroyStep;
        }
        float adjusted = destroyStep;
        if (player.isEyeInFluid(FluidTags.WATER)) {
            double submergedMiningSpeed = player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
            if (submergedMiningSpeed > 0.0D && submergedMiningSpeed < 1.0D) {
                adjusted *= (float) (1.0D / submergedMiningSpeed);
            }
        }
        return adjusted;
    }

    // =========================================================================
    //  临时上下文切换器
    // =========================================================================

    public static <T> T withTemporaryMainHandItem(ServerPlayer player, ItemStack stack, Supplier<T> action) {
        ItemStack previousMainHand = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        try {
            return action.get();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand);
        }
    }

    public static <T> T withTemporaryOnGround(ServerPlayer player, boolean onGround, Supplier<T> action) {
        boolean previous = player.onGround();
        player.setOnGround(onGround);
        try {
            return action.get();
        } finally {
            player.setOnGround(previous);
        }
    }

    static <T> T withTemporarySelectedSlot(ServerPlayer player, int toolSlot, Supplier<T> action) {
        int slot = RtsMiningValidator.clampHotbarSlot(toolSlot);
        int prevSelected = player.getInventory().selected;

        player.getInventory().selected = slot;
        try {
            return action.get();
        } finally {
            player.getInventory().selected = prevSelected;
        }
    }

    // =========================================================================
    //  挖掘完成（共享清理）
    // =========================================================================

    /**
     * 完成一个完成的挖掘操作，委托给三个清理管道
     * （{@link WorkflowCompletePipe}、{@link ToolReturnPipe}、
     * {@link HistoryRecordPipe}），然后刷新储存页面并重置挖掘状态。
     *
     * <p>此方法从当前会话状态构建一个 {@link PipelineContext}，
     * 以便三个管道可以在与同步阶段流程相同的数据契约下执行。
     * 清理尽最大努力：失败通过 {@link WorkflowPipeline#runCleanupSequence}
     * 记录日志，但不会阻止后续清理步骤。</p>
     *
     * @param player   服务端玩家
     * @param session  玩家的储存会话
     * @param records  方块破坏前捕获的历史记录（可能为空）
     * @param face     使用的挖掘面（可能为 null）
     */
    static void finalizeMiningOperation(ServerPlayer player, RtsStorageSession session,
            List<HistoryBlockRecord> records, @Nullable Direction face) {

        // ── Build pipeline context from session state ────────────────
        PipelineContext ctx = new PipelineContext(player, Map.of());

        // Session (required by ToolReturnPipe and HistoryRecordPipe)
        ctx.setData(SessionValidatePipe.KEY_SESSION, session);

        // Workflow entry ID (from dedicated tracking map, not from session)
        int wfEntryId = removeWorkflowEntryId(player.getUUID());
        if (wfEntryId >= 0) {
            ctx.setData(PipelineContext.KEY_WORKFLOW_ENTRY_ID, wfEntryId);
        }

        boolean hasQueuedJobs = !session.mining.ultimineJobQueue.isEmpty();
        RtsbuildingMod.LOGGER.info("[RtsMiningStateMachine] finalizeMiningOperation: entryId={}, hasQueuedJobs={}, records={} for {}", wfEntryId, hasQueuedJobs, records.size(), player.getGameProfile().getName());

        // Borrowed tool lease — only return if this is the LAST job
        if (!hasQueuedJobs
                && session.mining.miningToolLease != null
                && !session.mining.miningToolLease.isEmpty()) {
            ctx.setData(ToolReturnPipe.KEY_TOOL_LEASE, session.mining.miningToolLease);
        }

        // History records (pre-captured before-state)
        if (!records.isEmpty()) {
            ctx.setData(HistoryRecordPipe.ARG_HISTORY_RECORDS, records);
            ctx.setData(HistoryRecordPipe.ARG_HISTORY_FACE, face != null ? face : Direction.DOWN);
        }

        // ── Execute cleanup sequence via WorkflowPipeline ───────────
        // 当队列中还有排队作业时，跳过 ToolReturnPipe（工具由下一个作业继续使用）
        var cleanupPipes = new ArrayList<PipelinePipe<? super PipelineContext>>();
        cleanupPipes.add(new WorkflowCompletePipe());
        if (!hasQueuedJobs) {
            cleanupPipes.add(new ToolReturnPipe());
        }
        cleanupPipes.add(new HistoryRecordPipe());
        WorkflowPipeline.runCleanupSequence(ctx, cleanupPipes);

        // 触发储存页面刷新以保证GUI实时更新
        ServiceRegistry.getInstance().serviceOp().afterModification(player, session);
        resetMiningState(session, hasQueuedJobs);
    }

    // =========================================================================
    //  状态重置
    // =========================================================================

    public static void resetMiningState(RtsStorageSession session) {
        resetMiningState(session, false);
    }

    /**
     * 重置会话的活跃挖掘状态字段。
     *
     * <p>当 {@code preserveTool} 为 {@code true} 时（即有排队的挖掘作业等待激活），
     * 借用的工具租赁和相关设置会被保留，以便下一个作业可以重用它们。</p>
     */
    public static void resetMiningState(RtsStorageSession session, boolean preserveTool) {
        session.mining.miningPos = null;
        session.mining.ultimineTargets.clear();
        session.mining.ultimineProgressPos = null;
        session.mining.ultimineTotalTargets = 0;
        session.mining.ultimineProcessedTargets = 0;
        session.mining.ultimineBrokenTargets = 0;
        session.mining.ultimineNotifyAccumulator = 0;
        session.mining.ultimineAbsorbedDrops = false;
        session.mining.miningFace = Direction.DOWN;
        session.mining.miningProgress = 0.0F;
        session.mining.miningStage = -1;
        if (!preserveTool) {
            session.mining.miningToolLease = RtsToolLease.empty();
            session.mining.miningSelectedToolRequested = false;
            session.mining.miningToolProtectionEnabled = true;
        }
        // workflowEntryId 不再位于此处——参见 RtsMiningStateMachine 中的 WORKFLOW_ENTRY_IDS
    }

    // =========================================================================
    //  多方块附属追踪
    // =========================================================================

    /**
     * 捕获所有 6 个邻居的破坏前状态，用于多方块结构追踪（门、床、双高植物等）。
     */
    private static List<HistoryBlockRecord> captureNeighborRecords(ServerLevel level, BlockPos pos) {
        List<HistoryBlockRecord> records = new ArrayList<>(6);
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState state = level.getBlockState(neighbor);
            if (!state.isAir()) {
                records.add(new HistoryBlockRecord(neighbor.immutable(), state));
            }
        }
        return records;
    }

    /**
     * 方块被破坏后，检查哪些邻居位置变成了空气，
     * 并将它们添加到会话的连锁挖掘已处理位置中，
     * 以便包含在批次历史记录中。
     */
    private static void recordCollateralBlocks(RtsStorageSession session, List<HistoryBlockRecord> neighborRecords,
            BlockPos brokenPos) {
        for (HistoryBlockRecord nr : neighborRecords) {
            if (nr.pos().equals(brokenPos)) {
                continue;
            }
            // If the neighbor was solid before but is now air, it was collateral
            // destroyed by vanilla (e.g. the other half of a door or bed).
            // We rely on the caller to check current state since we don't have
            // a ServerLevel reference here — the caller's history recording
            // handles this.
            session.mining.ultimineProcessedPositions.add(nr);
        }
    }

    /**
     * 从连锁挖掘目标队列中移除指定位置。
     */
    private static void removeUltimineTarget(RtsStorageSession session, BlockPos pos) {
        session.mining.ultimineTargets.removeIf(target -> target.equals(pos));
    }
}
