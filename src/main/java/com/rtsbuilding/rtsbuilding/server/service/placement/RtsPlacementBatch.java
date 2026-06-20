package com.rtsbuilding.rtsbuilding.server.service.placement;

import com.rtsbuilding.rtsbuilding.network.builder.C2SRtsPlaceBatchPayload;
import com.rtsbuilding.rtsbuilding.progression.RtsFeature;
import com.rtsbuilding.rtsbuilding.server.history.ServerHistoryManager;
import com.rtsbuilding.rtsbuilding.server.progression.RtsProgressionManager;
import com.rtsbuilding.rtsbuilding.server.service.RtsPendingPlacementService;
import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.service.ServiceRegistry;
import com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 批处理放置作业管理器，负责远程方块放置的排队和每 tick 节流处理。
 *
 * <p>管理批处理作业的完整生命周期：将放置请求排队为 {@link PlaceBatchJob}，
 * 通过 {@link #tickPlaceBatchJobs} 以每 tick 最多 {@value #BUILD_BATCH_MAX_BLOCKS_PER_TICK}
 * 个方块的速度节流处理，以及作业的暂停/恢复/完成流程。
 *
 * <p>快速建造作业（形状建造）受 {@link #BUILD_BATCH_MAX_QUEUED_JOBS}=4 限制，
 * 单个方块放置无限制。作业通过 NBT 序列化支持会话持久化。
 *
 * <p>不负责：单方块放置逻辑（{@link RtsPlacementExecutor}）、
 * 状态计划预解析（{@link RtsPlacementQuickBuild}）、
 * 物品提取（{@link RtsPlacementExtractor}）、声音（{@link RtsPlacementSound}）。
 */
public final class RtsPlacementBatch {
    private static final int BUILD_BATCH_MAX_BLOCKS_PER_TICK = 64;
    private static final int BUILD_BATCH_MAX_QUEUED_JOBS = 4;

    private RtsPlacementBatch() {
    }

    /**
     * Queues a batch of positions for remote placement. Sanitises input,
     * validates progression access, and caps the batch at
     * {@link C2SRtsPlaceBatchPayload#MAX_POSITIONS} positions.
     * <p>
     * Quick-build jobs (shape builds) are limited to
     * {@link #BUILD_BATCH_MAX_QUEUED_JOBS} queued jobs; when the queue is full,
     * new quick-build jobs are rejected. Single-block placements
     * ({@code quickBuild = false}) bypass this limit.
     */
    /**
     * Queues a batch of positions for remote placement.
     *
     * @return {@code true} if the job was actually queued; {@code false} if the
     *         job was silently skipped (progression locked, no valid positions,
     *         or quick-build queue full).  Callers should use this return value
     *         to decide whether to complete the associated workflow entry.
     */
    public static boolean enqueuePlaceBatch(ServerPlayer player, RtsStorageSession session, List<BlockPos> clickedPositions,
            Direction face, double hitOffsetX, double hitOffsetY, double hitOffsetZ, byte rotateSteps,
            boolean forcePlace, boolean skipIfOccupied, String itemId, ItemStack itemPrototype,
            double rayOriginX, double rayOriginY, double rayOriginZ, double rayDirX, double rayDirY,
            double rayDirZ, boolean quickBuild, boolean forceEmptyHand, boolean sendRemoteHint,
            int workflowEntryId) {
        if (!RtsProgressionManager.canUse(
                player, RtsFeature.REMOTE_PLACE)) {
            return false;
        }
        if (session == null || clickedPositions == null || clickedPositions.isEmpty() || face == null) {
            return false;
        }
        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        List<BlockPos> positions = new ArrayList<>(Math.min(clickedPositions.size(), C2SRtsPlaceBatchPayload.MAX_POSITIONS));
        for (BlockPos pos : clickedPositions) {
            if (pos == null || !RtsLinkedStorageResolver.canAccessWorldTarget(player, pos)) {
                continue;
            }
            positions.add(pos.immutable());
            if (positions.size() >= C2SRtsPlaceBatchPayload.MAX_POSITIONS) {
                break;
            }
        }
        if (positions.isEmpty()) {
            return false;
        }
        // Quick-build jobs (shape builds) are limited to BUILD_BATCH_MAX_QUEUED_JOBS;
        // reject when full. Single-block placements bypass this limit.
        if (quickBuild && session.placement.placeBatchJobs.size() >= BUILD_BATCH_MAX_QUEUED_JOBS) {
            return false;
        }
        session.placement.placeBatchJobs.addLast(new PlaceBatchJob(
                positions,
                face,
                RtsPlacementHelper.sanitizeHitOffset(hitOffsetX, face, Direction.Axis.X),
                RtsPlacementHelper.sanitizeHitOffset(hitOffsetY, face, Direction.Axis.Y),
                RtsPlacementHelper.sanitizeHitOffset(hitOffsetZ, face, Direction.Axis.Z),
                rotateSteps,
                forcePlace,
                skipIfOccupied,
                itemId == null ? "" : itemId,
                RtsPlacementExtractor.sanitizePrototype(itemId, itemPrototype),
                rayOriginX,
                rayOriginY,
                rayOriginZ,
                rayDirX,
                rayDirY,
                rayDirZ,
                quickBuild,
                forceEmptyHand,
                sendRemoteHint,
                workflowEntryId));
        return true;
    }

    /**
     * Tick 处理器，从排队的批处理作业中处理最多 {@link #BUILD_BATCH_MAX_BLOCKS_PER_TICK}
     * 个方块。快速建造作业使用预解析的状态计划快速路径；
     * 其他所有作业走交互式单放置路径。
     * 当一个完整作业完成时保存并刷新会话。
     */
    public static void tickPlaceBatchJobs(ServerPlayer player, RtsStorageSession session) {
        if (player == null || session == null || session.placement.placeBatchJobs.isEmpty()) return;

        int budget = BUILD_BATCH_MAX_BLOCKS_PER_TICK;
        int pausedConsecutive = 0;
        int totalBlocks = 0;
        for (PlaceBatchJob j : session.placement.placeBatchJobs) totalBlocks += j.totalCount();
        if (totalBlocks <= 0) return;
        budget = Math.min(budget, Math.max(1, totalBlocks / 10));

        Map<Integer, Integer> placedBeforeTick = new HashMap<>();
        List<PlaceBatchJob> fullyCompletedJobs = new ArrayList<>();
        for (PlaceBatchJob j : session.placement.placeBatchJobs) {
            placedBeforeTick.put(j.workflowEntryId(), j.placedPositions.size());
        }

        while (budget > 0 && !session.placement.placeBatchJobs.isEmpty()) {
            PlaceBatchJob job = session.placement.placeBatchJobs.peekFirst();
            boolean hasWorkflow = hasWorkflowEntry(job);
            Optional<RtsWorkflowToken> tokenOpt = workflowToken(player, job);

            if (hasWorkflow && tokenOpt.isEmpty()) {
                session.placement.placeBatchJobs.removeFirst();
                pausedConsecutive = 0;
                continue;
            }
            if (hasWorkflow && tokenOpt.get().isPaused()) {
                session.placement.placeBatchJobs.removeFirst();
                session.placement.placeBatchJobs.addLast(job);
                pausedConsecutive++;
                if (pausedConsecutive >= session.placement.placeBatchJobs.size()) break;
                continue;
            }
            pausedConsecutive = 0;

            switch (job.phase) {
                case MATERIAL_CALC -> {
                    job.required.clear();
                    job.prototypes.clear();
                    job.missingItems.clear();
                    int need = job.remainingCount();
                    if (need > 0) {
                        job.required.put(job.itemId(), need);
                        if (job.itemPrototype() != null && !job.itemPrototype().isEmpty()) {
                            job.prototypes.put(job.itemId(), job.itemPrototype());
                        }
                        // 预检存储可用量——提前跳过，不等到 BUFFER_FILLING 才失败
                        var aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
                        if (aggregate != null && !aggregate.isEmpty()) {
                            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                                    net.minecraft.resources.ResourceLocation.tryParse(job.itemId()));
                            long available = item != null
                                    ? (job.itemPrototype() != null && !job.itemPrototype().isEmpty()
                                            ? aggregate.getTotalCount(job.itemPrototype().getItem())
                                            : aggregate.getTotalCount(item))
                                    : 0L;
                            if (available <= 0) {
                                job.missingItems.add(job.itemId());
                                // 存储中没有此物品 → 直接跳过，后续提示玩家提交补充
                                if (hasWorkflow) {
                                    session.placement.placeBatchJobs.removeFirst();
                                    session.placement.pendingJobs.addLast(job);
                                    job.phase = BatchPhase.SUSPENDED;
                                    tokenOpt.ifPresent(token -> token.suspend());
                                    player.displayClientMessage(
                                            net.minecraft.network.chat.Component.literal(
                                                    "§e缺少材料: §f" + job.itemId() + " §7(未在存储中找到)"),
                                            false);
                                    break; // 跳出 switch
                                }
                            }
                        }
                    }
                    job.buffer = new RtsPlaceBuffer();
                    job.phase = BatchPhase.BUFFER_FILLING;
                }
                case BUFFER_FILLING -> {
                    session.placement.tickBuffer = job.buffer;
                    int filled = job.buffer.fillFromStorage(player, session, job.required, job.prototypes, Math.min(budget, BUILD_BATCH_MAX_BLOCKS_PER_TICK));
                    if (filled > 0) budget = Math.max(0, budget - filled);
                    // 全部需求满足才允许进入建造阶段——禁止提取和建造在同一 tick 穿插
                    if (job.required.isEmpty()) {
                        job.phase = BatchPhase.BUILDING;
                        // 填装完成触发了存储提取——此时刷新一次，建造阶段不再刷新
                        ServiceRegistry.getInstance().serviceOp().markDirty(player, session);
                        ServiceRegistry.getInstance().serviceOp().refreshPage(player, session);
                    } else if (!job.buffer.isEmpty()) {
                        // 有进度但不够，下一 tick 继续提取
                    } else {
                        // 存储完全没有需要的东西 → 搁置到 pendingJobs
                        job.missingItems.addAll(job.required.keySet());
                        session.placement.placeBatchJobs.removeFirst();
                        session.placement.pendingJobs.addLast(job);
                        job.phase = BatchPhase.SUSPENDED;
                        tokenOpt.ifPresent(token -> token.suspend());
                    }
                }
                case BUILDING -> {
                    session.placement.tickBuffer = job.buffer;
                    boolean madeProgress = false;
                    while (budget > 0 && job.hasNext()) {
                        BlockPos clickedPos = job.next();
                        RtsPlacementQuickBuild.StatePlacementPlan statePlan = job.quickBuild()
                                ? job.statePlacementPlan(player) : null;
                        boolean keepGoing;
                        if (statePlan != null) {
                            BlockPos trackedPos = clickedPos;
                            BlockState beforeState = player.serverLevel().getBlockState(trackedPos);
                            keepGoing = RtsPlacementQuickBuild.placeStateBatchEntry(player, session, clickedPos, statePlan);
                            if (keepGoing && (beforeState.isAir() || beforeState.canBeReplaced())
                                    && !player.serverLevel().getBlockState(trackedPos).isAir()) {
                                job.placedPositions.add(trackedPos);
                            } else if (keepGoing) {
                                job.skippedWhileProcessing++;
                            }
                        } else {
                            Vec3 hitLocation = new Vec3(
                                    clickedPos.getX() + job.hitOffsetX(),
                                    clickedPos.getY() + job.hitOffsetY(),
                                    clickedPos.getZ() + job.hitOffsetZ());
                            BlockPos adjPos = clickedPos.relative(job.face());
                            BlockState beforeClicked = player.serverLevel().getBlockState(clickedPos);
                            BlockState beforeAdjacent = player.serverLevel().hasChunkAt(adjPos)
                                    ? player.serverLevel().getBlockState(adjPos) : null;
                            keepGoing = RtsPlacementExecutor.placeSelectedInternal(
                                    player, session, clickedPos, job.face(),
                                    hitLocation.x, hitLocation.y, hitLocation.z,
                                    job.rotateSteps(), job.forcePlace(), job.skipIfOccupied(),
                                    job.itemId(), job.itemPrototype(),
                                    job.rayOriginX(), job.rayOriginY(), job.rayOriginZ(),
                                    job.rayDirX(), job.rayDirY(), job.rayDirZ(),
                                    job.quickBuild(), job.forceEmptyHand(), false, job.sendRemoteHint());
                            if (keepGoing) {
                                BlockPos actualPos = RtsPlacementHelper.detectPlacedPos(
                                        player.serverLevel(), clickedPos, beforeClicked, adjPos, beforeAdjacent);
                                if (actualPos != null) {
                                    job.placedPositions.add(actualPos);
                                } else {
                                    job.skippedWhileProcessing++;
                                }
                            }
                        }
                        budget--;
                        if (!keepGoing) {
                            if (hasWorkflow) {
                                job.unconsumeLast();
                                budget--;
                                session.placement.placeBatchJobs.removeFirst();
                                session.placement.pendingJobs.addLast(job);
                                tokenOpt.ifPresent(token -> token.suspend());
                            } else {
                                // 无工作流的作业：走完成路径
                                job.phase = BatchPhase.COMPLETING;
                            }
                            break;
                        }
                        madeProgress = true;
                    }
                    if (!job.hasNext()) {
                        job.phase = BatchPhase.COMPLETING;
                    } else if (!madeProgress && !job.buffer.hasItems()) {
                        // buffer 应为满的——耗尽说明存储被外部修改
                        job.missingItems.add(job.itemId());
                        session.placement.placeBatchJobs.removeFirst();
                        session.placement.pendingJobs.addLast(job);
                        job.phase = BatchPhase.SUSPENDED;
                        tokenOpt.ifPresent(token -> token.suspend());
                    }
                }
                case SUSPENDED -> {
                    // 搁置的作业——移入 pendingJobs
                    session.placement.placeBatchJobs.removeFirst();
                    session.placement.pendingJobs.addLast(job);
                }
                case COMPLETING -> {
                    // 结束阶段：flush 缓冲区余量，记录完成，从队列移除
                    if (job.buffer != null && !job.buffer.isEmpty()) {
                        job.buffer.flush(player, session);
                    }
                    job.buffer = null;
                    session.placement.placeBatchJobs.removeFirst();
                    fullyCompletedJobs.add(job);
                }
            }
        }

        // 处理完成的 job
        for (PlaceBatchJob completedJob : fullyCompletedJobs) {
            int before = placedBeforeTick.getOrDefault(completedJob.workflowEntryId(), 0);
            int delta = completedJob.placedPositions.size() - before;
            if (!completedJob.placedPositions.isEmpty()) {
                ServerHistoryManager.recordPlacement(player, completedJob.placedPositions, completedJob.face());
            }
            if (delta > 0) {
                workflowToken(player, completedJob).ifPresent(token -> token.updateProgress(delta, null));
            }
            int failed = completedJob.skippedWhileProcessing;
            if (failed > 0) {
                var token = RtsWorkflowEngine.getInstance().from(player, completedJob.workflowEntryId());
                for (int i = 0; i < failed; i++) token.ifPresent(t -> t.recordFailure());
            }
            workflowToken(player, completedJob).ifPresent(token -> token.complete());
        }
        if (!fullyCompletedJobs.isEmpty()) {
            ServiceRegistry.getInstance().serviceOp().afterModification(player, session);
        }

        // 更新中途进度——建造阶段不再触发页面刷新（材料在缓冲区，存储未变）
        for (PlaceBatchJob j : session.placement.placeBatchJobs) {
            int before = placedBeforeTick.getOrDefault(j.workflowEntryId(), 0);
            int delta = j.placedPositions.size() - before;
            if (delta > 0) {
                workflowToken(player, j).ifPresent(token -> token.updateProgress(delta, null));
            }
        }
        RtsPendingPlacementService.refreshWorkflowProgress(player, session);

        // 清除 tick 级引用
        session.placement.tickBuffer = null;
    }

    private static boolean hasWorkflowEntry(PlaceBatchJob job) {
        return job.workflowEntryId() >= 0;
    }

    private static Optional<RtsWorkflowToken> workflowToken(ServerPlayer player, PlaceBatchJob job) {
        return hasWorkflowEntry(job)
                ? RtsWorkflowEngine.getInstance().from(player, job.workflowEntryId())
                : Optional.empty();
    }

    /**
     * 批量放置作业的生命周期阶段。
     */
    public enum BatchPhase {
        /** 初始：计算材料清单 */
        MATERIAL_CALC,
        /** 从存储渐进提取物品到缓冲区 */
        BUFFER_FILLING,
        /** 从缓冲区取料，放置方块 */
        BUILDING,
        /** 材料不足，搁置到 pendingJobs 等待玩家补充 */
        SUSPENDED,
        /** 完成：flush 缓冲区余量，清理 */
        COMPLETING
    }

    /**
     * 单个批处理放置作业，持有共享的放置参数和有序的目标位置列表。
     * 每个作业由 {@link #tickPlaceBatchJobs} 以每 tick 最多
     * {@link #BUILD_BATCH_MAX_BLOCKS_PER_TICK} 个方块的速度处理。
     */
    public static final class PlaceBatchJob {
        private final List<BlockPos> clickedPositions;
        private final Direction face;
        private final double hitOffsetX;
        private final double hitOffsetY;
        private final double hitOffsetZ;
        private final byte rotateSteps;
        private final boolean forcePlace;
        private final boolean skipIfOccupied;
        private final String itemId;
        private final ItemStack itemPrototype;
        private final double rayOriginX;
        private final double rayOriginY;
        private final double rayOriginZ;
        private final double rayDirX;
        private final double rayDirY;
        private final double rayDirZ;
        private final boolean quickBuild;
        private final boolean forceEmptyHand;
        private final boolean sendRemoteHint;
        /** The unique entry ID of the workflow entry associated with this job. */
        private final int workflowEntryId;
        private int index;
        private boolean statePlanResolved;
        private RtsPlacementQuickBuild.StatePlacementPlan statePlan;
        final List<BlockPos> placedPositions = new ArrayList<>();
        int skippedWhileProcessing;

        /** 生命周期阶段 */
        BatchPhase phase = BatchPhase.MATERIAL_CALC;
        /** 剩余需求：itemId → 仍需要的总数 */
        final Map<String, Integer> required = new LinkedHashMap<>();
        /** 物品原型（保留 NBT） */
        final Map<String, ItemStack> prototypes = new HashMap<>();
        /** 跨 tick 缓冲（BUFFER_FILLING 填装，BUILDING 消耗，COMPLETING 清空） */
        RtsPlaceBuffer buffer;
        /** 存储中缺失的物品——suspend 时用于通知玩家 */
        final List<String> missingItems = new ArrayList<>();

        private PlaceBatchJob(List<BlockPos> clickedPositions, Direction face, double hitOffsetX, double hitOffsetY,
                double hitOffsetZ, byte rotateSteps, boolean forcePlace, boolean skipIfOccupied, String itemId,
                ItemStack itemPrototype, double rayOriginX, double rayOriginY, double rayOriginZ, double rayDirX,
                double rayDirY, double rayDirZ, boolean quickBuild, boolean forceEmptyHand, boolean sendRemoteHint,
                int workflowEntryId) {
            this.clickedPositions = clickedPositions;
            this.face = face;
            this.hitOffsetX = hitOffsetX;
            this.hitOffsetY = hitOffsetY;
            this.hitOffsetZ = hitOffsetZ;
            this.rotateSteps = rotateSteps;
            this.forcePlace = forcePlace;
            this.skipIfOccupied = skipIfOccupied;
            this.itemId = itemId;
            this.itemPrototype = itemPrototype == null ? ItemStack.EMPTY : itemPrototype.copy();
            this.rayOriginX = rayOriginX;
            this.rayOriginY = rayOriginY;
            this.rayOriginZ = rayOriginZ;
            this.rayDirX = rayDirX;
            this.rayDirY = rayDirY;
            this.rayDirZ = rayDirZ;
            this.quickBuild = quickBuild;
            this.forceEmptyHand = forceEmptyHand;
            this.sendRemoteHint = sendRemoteHint;
            this.workflowEntryId = workflowEntryId;
        }

        private boolean hasNext() {
            return this.index < this.clickedPositions.size();
        }

        int remainingCount() {
            return this.clickedPositions.size() - this.index;
        }

        int totalCount() {
            return this.clickedPositions.size();
        }

        private BlockPos next() {
            return this.clickedPositions.get(this.index++);
        }

        /**
         * 返回剩余（未处理）位置的不可变列表。
         */
        public List<BlockPos> remainingPositions() {
            return this.clickedPositions.subList(this.index, this.clickedPositions.size());
        }

        /** 记录一个已成功放置的位置。 */
        public void markPlaced(BlockPos pos) {
            if (pos != null) {
                this.placedPositions.add(pos);
            }
        }

        /** 放置失败时回退索引，下个 tick 重试同一位置 */
        void unconsumeLast() {
            if (this.index > 0) {
                this.index--;
            }
        }

        /** 跳过当前一个位置（用于冲突跳过或已手动放置跳过） */
        public void skipOne() {
            if (hasNext()) {
                this.index++;
            }
        }

        /** 返回当前处理到的索引位置 */
        public int getIndex() {
            return this.index;
        }

        /** 返回本 job 对应的工作流条目 ID (entry.id, 不可变) */
        public int workflowEntryId() {
            return this.workflowEntryId;
        }

        /** 重置生命周期阶段，用于 resume 时重新计算材料 */
        public void resetPhase() {
            this.phase = BatchPhase.MATERIAL_CALC;
            this.required.clear();
            this.missingItems.clear();
        }

        /** 返回所有点击位置列表（不可修改） */
        public List<BlockPos> clickedPositions() {
            return java.util.Collections.unmodifiableList(this.clickedPositions);
        }

        // ──────────────────────────────────────────────────────────
        //  NBT 序列化——用于会话持久化
        // ──────────────────────────────────────────────────────────

        private static final String NBT_POSITIONS = "positions";
        private static final String NBT_FACE = "face";
        private static final String NBT_HIT_OFFSET_X = "hitOffsetX";
        private static final String NBT_HIT_OFFSET_Y = "hitOffsetY";
        private static final String NBT_HIT_OFFSET_Z = "hitOffsetZ";
        private static final String NBT_ROTATE_STEPS = "rotateSteps";
        private static final String NBT_FORCE_PLACE = "forcePlace";
        private static final String NBT_SKIP_IF_OCCUPIED = "skipIfOccupied";
        private static final String NBT_ITEM_ID = "itemId";
        private static final String NBT_ITEM_PROTOTYPE = "itemPrototype";
        private static final String NBT_RAY_ORIGIN_X = "rayOriginX";
        private static final String NBT_RAY_ORIGIN_Y = "rayOriginY";
        private static final String NBT_RAY_ORIGIN_Z = "rayOriginZ";
        private static final String NBT_RAY_DIR_X = "rayDirX";
        private static final String NBT_RAY_DIR_Y = "rayDirY";
        private static final String NBT_RAY_DIR_Z = "rayDirZ";
        private static final String NBT_QUICK_BUILD = "quickBuild";
        private static final String NBT_FORCE_EMPTY_HAND = "forceEmptyHand";
        private static final String NBT_SEND_REMOTE_HINT = "sendRemoteHint";
        private static final String NBT_WORKFLOW_ENTRY_ID = "workflowEntryId";
        private static final String NBT_INDEX = "index";

        /**
         * 将此批处理作业序列化为 {@link CompoundTag} 用于持久化存储。
         */
        public CompoundTag toNbt(net.minecraft.core.RegistryAccess registryAccess) {
            CompoundTag tag = new CompoundTag();
            long[] posArray = new long[clickedPositions.size()];
            for (int i = 0; i < clickedPositions.size(); i++) {
                posArray[i] = clickedPositions.get(i).asLong();
            }
            tag.putLongArray(NBT_POSITIONS, posArray);
            tag.putByte(NBT_FACE, (byte) face.get3DDataValue());
            tag.putDouble(NBT_HIT_OFFSET_X, hitOffsetX);
            tag.putDouble(NBT_HIT_OFFSET_Y, hitOffsetY);
            tag.putDouble(NBT_HIT_OFFSET_Z, hitOffsetZ);
            tag.putByte(NBT_ROTATE_STEPS, rotateSteps);
            tag.putBoolean(NBT_FORCE_PLACE, forcePlace);
            tag.putBoolean(NBT_SKIP_IF_OCCUPIED, skipIfOccupied);
            tag.putString(NBT_ITEM_ID, itemId);
            if (!itemPrototype.isEmpty()) {
                tag.put(NBT_ITEM_PROTOTYPE, itemPrototype.save(registryAccess));
            }
            tag.putDouble(NBT_RAY_ORIGIN_X, rayOriginX);
            tag.putDouble(NBT_RAY_ORIGIN_Y, rayOriginY);
            tag.putDouble(NBT_RAY_ORIGIN_Z, rayOriginZ);
            tag.putDouble(NBT_RAY_DIR_X, rayDirX);
            tag.putDouble(NBT_RAY_DIR_Y, rayDirY);
            tag.putDouble(NBT_RAY_DIR_Z, rayDirZ);
            tag.putBoolean(NBT_QUICK_BUILD, quickBuild);
            tag.putBoolean(NBT_FORCE_EMPTY_HAND, forceEmptyHand);
            tag.putBoolean(NBT_SEND_REMOTE_HINT, sendRemoteHint);
            tag.putInt(NBT_WORKFLOW_ENTRY_ID, workflowEntryId);
            tag.putInt(NBT_INDEX, index);
            return tag;
        }

        /**
         * 从 {@link CompoundTag} 反序列化 {@link PlaceBatchJob}。
         */
        public static PlaceBatchJob fromNbt(CompoundTag tag, net.minecraft.core.RegistryAccess registryAccess) {
            long[] posArray = tag.getLongArray(NBT_POSITIONS);
            List<BlockPos> positions = new ArrayList<>(posArray.length);
            for (long l : posArray) {
                positions.add(BlockPos.of(l));
            }
            Direction face = Direction.from3DDataValue(tag.getByte(NBT_FACE));
            double hitOffsetX = tag.getDouble(NBT_HIT_OFFSET_X);
            double hitOffsetY = tag.getDouble(NBT_HIT_OFFSET_Y);
            double hitOffsetZ = tag.getDouble(NBT_HIT_OFFSET_Z);
            byte rotateSteps = tag.getByte(NBT_ROTATE_STEPS);
            boolean forcePlace = tag.getBoolean(NBT_FORCE_PLACE);
            boolean skipIfOccupied = tag.getBoolean(NBT_SKIP_IF_OCCUPIED);
            String itemId = tag.getString(NBT_ITEM_ID);
            ItemStack itemPrototype = ItemStack.EMPTY;
            if (tag.contains(NBT_ITEM_PROTOTYPE, Tag.TAG_COMPOUND)) {
                itemPrototype = ItemStack.parseOptional(registryAccess, tag.getCompound(NBT_ITEM_PROTOTYPE));
            }
            double rayOriginX = tag.getDouble(NBT_RAY_ORIGIN_X);
            double rayOriginY = tag.getDouble(NBT_RAY_ORIGIN_Y);
            double rayOriginZ = tag.getDouble(NBT_RAY_ORIGIN_Z);
            double rayDirX = tag.getDouble(NBT_RAY_DIR_X);
            double rayDirY = tag.getDouble(NBT_RAY_DIR_Y);
            double rayDirZ = tag.getDouble(NBT_RAY_DIR_Z);
            boolean quickBuild = tag.getBoolean(NBT_QUICK_BUILD);
            boolean forceEmptyHand = tag.getBoolean(NBT_FORCE_EMPTY_HAND);
            boolean sendRemoteHint = tag.getBoolean(NBT_SEND_REMOTE_HINT);
            int workflowEntryId = tag.getInt(NBT_WORKFLOW_ENTRY_ID);
            int index = tag.getInt(NBT_INDEX);

            PlaceBatchJob job = new PlaceBatchJob(
                    positions, face, hitOffsetX, hitOffsetY, hitOffsetZ,
                    rotateSteps, forcePlace, skipIfOccupied, itemId, itemPrototype,
                    rayOriginX, rayOriginY, rayOriginZ, rayDirX, rayDirY, rayDirZ,
                    quickBuild, forceEmptyHand, sendRemoteHint, workflowEntryId);
            job.index = index;
            return job;
        }

        BlockPos templatePosition() {
            return this.clickedPositions.isEmpty() ? null : this.clickedPositions.get(0);
        }

        BlockHitResult templateHit(BlockPos templatePos) {
            return new BlockHitResult(
                    new Vec3(
                            templatePos.getX() + this.hitOffsetX,
                            templatePos.getY() + this.hitOffsetY,
                            templatePos.getZ() + this.hitOffsetZ),
                    this.face,
                    templatePos,
                    false);
        }

        private RtsPlacementQuickBuild.StatePlacementPlan statePlacementPlan(ServerPlayer player) {
            if (!this.statePlanResolved) {
                this.statePlan = RtsPlacementQuickBuild.resolveStatePlacementPlan(player, this);
                this.statePlanResolved = true;
            }
            return this.statePlan;
        }

        public Direction face() {
            return this.face;
        }

        public double hitOffsetX() {
            return this.hitOffsetX;
        }

        public double hitOffsetY() {
            return this.hitOffsetY;
        }

        public double hitOffsetZ() {
            return this.hitOffsetZ;
        }

        public byte rotateSteps() {
            return this.rotateSteps;
        }

        public boolean forcePlace() {
            return this.forcePlace;
        }

        public boolean skipIfOccupied() {
            return this.skipIfOccupied;
        }

        public String itemId() {
            return this.itemId;
        }

        public ItemStack itemPrototype() {
            return this.itemPrototype.copy();
        }

        public double rayOriginX() {
            return this.rayOriginX;
        }

        public double rayOriginY() {
            return this.rayOriginY;
        }

        public double rayOriginZ() {
            return this.rayOriginZ;
        }

        public double rayDirX() {
            return this.rayDirX;
        }

        public double rayDirY() {
            return this.rayDirY;
        }

        public double rayDirZ() {
            return this.rayDirZ;
        }

        public boolean quickBuild() {
            return this.quickBuild;
        }

        public boolean forceEmptyHand() {
            return this.forceEmptyHand;
        }

        private boolean sendRemoteHint() {
            return this.sendRemoteHint;
        }
    }
}
