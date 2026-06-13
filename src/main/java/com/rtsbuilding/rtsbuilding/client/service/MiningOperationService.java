package com.rtsbuilding.rtsbuilding.client.service;

import com.rtsbuilding.rtsbuilding.client.network.RtsClientPacketGateway;
import com.rtsbuilding.rtsbuilding.client.record.AreaMineBounds;
import com.rtsbuilding.rtsbuilding.client.screen.ultimine.AreaMineShape;
import com.rtsbuilding.rtsbuilding.common.shape.ShapeFillMode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class MiningOperationService {

    // =========================================================================
    //  常量
    // =========================================================================

    private static final int RTS_MINE_RENDER_ID = 0x525453;

    /** 范围挖掘阶段：未激活 */
    public static final int AREA_MINE_PHASE_NONE = 0;
    /** 范围挖掘阶段：等待第二次点击确定底面矩形 */
    public static final int AREA_MINE_PHASE_NEED_SECOND = 1;
    /** 范围挖掘阶段：等待滚轮调整高度后确认 */
    public static final int AREA_MINE_PHASE_NEED_HEIGHT = 2;
    /** 范围挖掘每个维度的最大方块数（12 = 单个方向最多延伸 11 格） */
    public static final int AREA_MINE_MAX_SIZE = 12;

    // =========================================================================
    //  挖掘状态字段
    // =========================================================================

    /** 当前正在挖掘的方块位置 */
    private BlockPos activeMinePos;
    /** 当前挖掘的方块面 */
    private int activeMineFace = -1;
    /** 当前挖掘使用的工具快捷栏槽位 */
    private int activeMineToolSlot;

    /** 挖掘破坏进度渲染位置 */
    private BlockPos mineRenderPos;
    /** 挖掘破坏进度渲染阶段 */
    private int mineRenderStage = -1;

    /** 最近完成的挖掘进度位置（用于完成动画） */
    private BlockPos mineProgressCompletedPos;
    /** 最近完成挖掘进度的系统时间戳 */
    private long mineProgressCompletedAtMs;

    /** Ultimine 整体进度已处理方块数。负值表示未激活。 */
    private int ultimineProgressProcessed = -1;
    /** Ultimine 整体进度总目标方块数。 */
    private int ultimineProgressTotal = 0;

    // =========================================================================
    //  范围挖掘（Area Mine）状态
    // =========================================================================

    /** 当前范围挖掘阶段 */
    private int areaMinePhase = AREA_MINE_PHASE_NONE;
    /** 锚点 A：第一次点击的位置（也是 Y 方向的基准面） */
    private BlockPos areaMinePointA;
    /** 锚点 B：第二次点击的位置，与 A 共同确定底面矩形范围 */
    private BlockPos areaMinePointB;
    /** 高度偏移量：基于 A 点 Y 坐标上下延伸（滚轮调节，正=向上，负=向下） */
    private int areaMineHeightOffset;

    /** 当前范围挖掘形状 */
    private AreaMineShape areaMineShape = AreaMineShape.CHAIN;

    // =========================================================================
    //  网络回调
    // =========================================================================

    /**
     * 应用来自服务端的方块挖掘进度更新。
     * 更新渲染破坏进度，负 stage 表示清除渲染。
     */
    public void applyMineProgress(BlockPos pos, int stage) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        if (stage < 0) {
            if (this.mineRenderPos != null) {
                minecraft.level.destroyBlockProgress(RTS_MINE_RENDER_ID, this.mineRenderPos, -1);
                this.mineRenderPos = null;
            } else {
                minecraft.level.destroyBlockProgress(RTS_MINE_RENDER_ID, pos, -1);
            }
            this.mineRenderStage = -1;
            return;
        }

        if (this.mineRenderPos != null && !this.mineRenderPos.equals(pos)) {
            minecraft.level.destroyBlockProgress(RTS_MINE_RENDER_ID, this.mineRenderPos, -1);
        }
        minecraft.level.destroyBlockProgress(RTS_MINE_RENDER_ID, pos, Math.min(9, stage));
        this.mineRenderPos = pos.immutable();
        this.mineRenderStage = Math.min(9, stage);
    }

    /**
     * 应用来自服务端的 ultimine 整体进度更新。
     */
    public void applyUltimineProgress(int processed, int total) {
        if (total > 0 && processed >= total && this.mineRenderPos != null) {
            rememberMineProgressCompleted(this.mineRenderPos);
        }
        this.ultimineProgressProcessed = processed;
        this.ultimineProgressTotal = total;
    }

    private void rememberMineProgressCompleted(BlockPos pos) {
        this.mineProgressCompletedPos = pos == null ? null : pos.immutable();
        this.mineProgressCompletedAtMs = System.currentTimeMillis();
    }

    // =========================================================================
    //  挖掘操作方法
    // =========================================================================

    /**
     * 开始单个方块挖掘。
     */
    public void startMining(BlockPos pos, int face, int toolSlot,
                            String selectedItemId, ItemStack selectedItemPreview,
                            boolean allowPlacedBlockRecovery, boolean toolProtectionEnabled) {
        if (pos == null) {
            return;
        }
        this.activeMinePos = pos.immutable();
        this.activeMineFace = face;
        this.activeMineToolSlot = Mth.clamp(toolSlot, 0, 8);
        this.mineRenderPos = this.activeMinePos;
        this.mineRenderStage = 0;
        RtsClientPacketGateway.sendMineStart(
                this.activeMinePos,
                face,
                this.activeMineToolSlot,
                selectedMiningToolItemId(selectedItemId, selectedItemPreview),
                selectedMiningToolPrototype(selectedItemId, selectedItemPreview),
                allowPlacedBlockRecovery,
                toolProtectionEnabled);
    }

    /**
     * 开始连锁（ultimine）挖掘。
     */
    public void startUltimine(BlockPos pos, int face, int toolSlot, int limit, byte mode,
                              String selectedItemId, ItemStack selectedItemPreview,
                              boolean toolProtectionEnabled) {
        if (pos == null) {
            return;
        }
        this.activeMinePos = pos.immutable();
        this.activeMineFace = face;
        this.activeMineToolSlot = Mth.clamp(toolSlot, 0, 8);
        this.mineRenderPos = this.activeMinePos;
        this.mineRenderStage = 0;
        RtsClientPacketGateway.sendUltimineStart(
                this.activeMinePos,
                face,
                this.activeMineToolSlot,
                selectedMiningToolItemId(selectedItemId, selectedItemPreview),
                selectedMiningToolPrototype(selectedItemId, selectedItemPreview),
                limit,
                mode,
                toolProtectionEnabled);
    }

    /** 挖掘进度由服务端维护，客户端无需每 tick 发包。 */
    public void continueMining(int toolSlot) {
        // no-op
    }

    /**
     * 中止当前挖掘操作。
     */
    public void abortMining(int toolSlot) {
        if (this.activeMinePos == null || this.activeMineFace < 0) {
            return;
        }
        RtsClientPacketGateway.sendMineAbort(this.activeMinePos, this.activeMineFace, toolSlot);
        this.activeMinePos = null;
        this.activeMineFace = -1;
        this.mineRenderStage = -1;
    }

    // =========================================================================
    //  范围挖掘（Area Mine）— 操作方法
    // =========================================================================

    // ---------- 状态查询 ----------

    public int getAreaMinePhase() {
        return this.areaMinePhase;
    }

    public BlockPos getAreaMinePointA() {
        return this.areaMinePointA;
    }

    public BlockPos getAreaMinePointB() {
        return this.areaMinePointB;
    }

    public int getAreaMineHeightOffset() {
        return this.areaMineHeightOffset;
    }

    // ---------- 边界计算 ----------

    /**
     * 根据两个对角点和高度偏移，计算范围挖掘的完整三维边界。
     * <p>以 pointA 为锚点：
     * <ul>
     *   <li>X/Z 方向：以 pointB 决定延伸方向，差值 clamp 到 [0, AREA_MINE_MAX_SIZE-1]</li>
     *   <li>Y 方向：baseY + heightOffset，再 clamp 到 [baseY-(MAX-1), baseY+(MAX-1)]</li>
     * </ul>
     *
     * @param pointA       锚点 A
     * @param pointB       对角点 B
     * @param heightOffset 高度偏移（正=向上延伸，负=向下延伸，0=仅单层底面）
     * @return 裁剪后的边界结果
     */
    public static AreaMineBounds computeAreaMineBounds(BlockPos pointA, BlockPos pointB, int heightOffset) {
        int dx = Math.min(Math.abs(pointB.getX() - pointA.getX()), AREA_MINE_MAX_SIZE - 1);
        int minX = pointB.getX() >= pointA.getX() ? pointA.getX() : pointA.getX() - dx;
        int maxX = pointB.getX() >= pointA.getX() ? pointA.getX() + dx : pointA.getX();

        int dz = Math.min(Math.abs(pointB.getZ() - pointA.getZ()), AREA_MINE_MAX_SIZE - 1);
        int minZ = pointB.getZ() >= pointA.getZ() ? pointA.getZ() : pointA.getZ() - dz;
        int maxZ = pointB.getZ() >= pointA.getZ() ? pointA.getZ() + dz : pointA.getZ();

        int baseY = pointA.getY();
        int minY = Math.max(baseY - (AREA_MINE_MAX_SIZE - 1), baseY + Math.min(0, heightOffset));
        int maxY = Math.min(baseY + (AREA_MINE_MAX_SIZE - 1), baseY + Math.max(0, heightOffset));

        return new AreaMineBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    // ---------- 高度设置 ----------

    public void setAreaMineHeightOffset(int offset) {
        this.areaMineHeightOffset = Math.max(-(AREA_MINE_MAX_SIZE - 1), Math.min(AREA_MINE_MAX_SIZE - 1, offset));
    }

    public void adjustAreaMineHeightOffset(int delta) {
        setAreaMineHeightOffset(this.areaMineHeightOffset + delta);
    }

    // ---------- 选区管理 ----------

    public void setAreaMinePointA(BlockPos pos, double anchorX, double anchorZ, double maxRadius, boolean hasBounds) {
        this.areaMinePointA = pos == null ? null : clampToBounds(pos.immutable(), anchorX, anchorZ, maxRadius, hasBounds);
        this.areaMinePointB = null;
        this.areaMineHeightOffset = 0;
        this.areaMinePhase = pos == null ? AREA_MINE_PHASE_NONE : AREA_MINE_PHASE_NEED_SECOND;
        this.mineRenderPos = this.areaMinePointA;
        this.mineRenderStage = 0;
    }

    public void setAreaMinePointB(BlockPos pos, double anchorX, double anchorZ, double maxRadius, boolean hasBounds) {
        this.areaMinePointB = pos == null ? null : clampToBounds(pos.immutable(), anchorX, anchorZ, maxRadius, hasBounds);
        this.areaMineHeightOffset = 0;
        this.areaMinePhase = pos == null ? AREA_MINE_PHASE_NONE : AREA_MINE_PHASE_NEED_HEIGHT;
        this.mineRenderPos = this.areaMinePointB;
        this.mineRenderStage = 0;
    }

    private BlockPos clampToBounds(BlockPos pos, double anchorX, double anchorZ, double maxRadius, boolean hasBounds) {
        if (pos == null || !hasBounds) {
            return pos;
        }
        int minBlockX = Mth.floor(anchorX - maxRadius);
        int maxBlockX = Mth.ceil(anchorX + maxRadius) - 1;
        int minBlockZ = Mth.floor(anchorZ - maxRadius);
        int maxBlockZ = Mth.ceil(anchorZ + maxRadius) - 1;
        return new BlockPos(
                Mth.clamp(pos.getX(), minBlockX, maxBlockX),
                pos.getY(),
                Mth.clamp(pos.getZ(), minBlockZ, maxBlockZ));
    }

    public void clearAreaMineSession() {
        this.areaMinePhase = AREA_MINE_PHASE_NONE;
        this.areaMinePointA = null;
        this.areaMinePointB = null;
        this.areaMineHeightOffset = 0;
        this.mineRenderStage = -1;
    }

    public void confirmAreaMine(int toolSlot, ShapeFillMode fillMode,
                                String selectedItemId, ItemStack selectedItemPreview,
                                boolean toolProtectionEnabled) {
        if (this.areaMinePointA == null || this.areaMinePointB == null) {
            return;
        }
        AreaMineBounds bounds = computeAreaMineBounds(
                this.areaMinePointA, this.areaMinePointB, this.areaMineHeightOffset);

        this.activeMinePos = this.areaMinePointA.immutable();
        this.activeMineFace = Direction.UP.get3DDataValue();
        this.activeMineToolSlot = Mth.clamp(toolSlot, 0, 8);
        this.mineRenderPos = this.activeMinePos;
        this.mineRenderStage = 0;

        RtsClientPacketGateway.sendAreaMine(
                bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                bounds.minZ(), bounds.maxZ(),
                this.activeMineToolSlot,
                selectedMiningToolItemId(selectedItemId, selectedItemPreview),
                selectedMiningToolPrototype(selectedItemId, selectedItemPreview),
                (byte) this.areaMineShape.ordinal(),
                (byte) (fillMode == null ? ShapeFillMode.FILL : fillMode).ordinal(),
                toolProtectionEnabled);

        clearAreaMineSession();
    }

    public void confirmShapeAreaDestroy(List<BlockPos> targets, int toolSlot,
                                        String selectedItemId, ItemStack selectedItemPreview,
                                        boolean toolProtectionEnabled) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        BlockPos first = targets.get(0).immutable();
        this.activeMinePos = first;
        this.activeMineFace = Direction.UP.get3DDataValue();
        this.activeMineToolSlot = Mth.clamp(toolSlot, 0, 8);
        this.mineRenderPos = first;
        this.mineRenderStage = 0;
        RtsClientPacketGateway.sendAreaDestroy(
                targets,
                this.activeMineToolSlot,
                selectedMiningToolItemId(selectedItemId, selectedItemPreview),
                selectedMiningToolPrototype(selectedItemId, selectedItemPreview),
                toolProtectionEnabled);
        clearAreaMineSession();
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    private String selectedMiningToolItemId(String selectedItemId, ItemStack selectedItemPreview) {
        ItemStack prototype = selectedMiningToolPrototype(selectedItemId, selectedItemPreview);
        if (prototype.isEmpty()) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(prototype.getItem());
        return id == null ? "" : id.toString();
    }

    private ItemStack selectedMiningToolPrototype(String selectedItemId, ItemStack selectedItemPreview) {
        if (selectedItemId == null || selectedItemId.isBlank() || selectedItemPreview == null || selectedItemPreview.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (selectedItemPreview.getItem() instanceof BlockItem) {
            return ItemStack.EMPTY;
        }
        ItemStack prototype = selectedItemPreview.copy();
        prototype.setCount(1);
        return prototype;
    }

    // =========================================================================
    //  进度查询
    // =========================================================================

    public int getMineProgressStage() {
        return this.mineRenderStage;
    }

    public int getUltimineProgressProcessed() {
        return this.ultimineProgressProcessed;
    }

    public int getUltimineProgressTotal() {
        return this.ultimineProgressTotal;
    }

    public BlockPos getMineProgressPos() {
        return this.mineRenderPos;
    }

    public BlockPos getMineProgressCompletedPos() {
        return this.mineProgressCompletedPos;
    }

    public long getMineProgressCompletedAtMs() {
        return this.mineProgressCompletedAtMs;
    }

    // =========================================================================
    //  形状访问
    // =========================================================================

    public AreaMineShape getAreaMineShape() {
        return this.areaMineShape;
    }

    public void setAreaMineShape(AreaMineShape shape) {
        this.areaMineShape = shape == null ? AreaMineShape.CHAIN : shape;
    }

    // =========================================================================
    //  状态重置（供 Controller 在启用/禁用/死亡时调用）
    // =========================================================================

    /** 清除所有挖掘状态（不处理渲染清除）。 */
    public void clearMiningState() {
        this.activeMinePos = null;
        this.activeMineFace = -1;
        this.mineRenderPos = null;
        this.mineRenderStage = -1;
        this.ultimineProgressProcessed = -1;
        this.ultimineProgressTotal = 0;
    }

    /** 清除挖掘渲染（包含 destroyBlockProgress 清理）并重置所有挖掘状态。 */
    public void clearMiningRenderState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && this.mineRenderPos != null) {
            minecraft.level.destroyBlockProgress(RTS_MINE_RENDER_ID, this.mineRenderPos, -1);
        }
        clearMiningState();
    }
}
