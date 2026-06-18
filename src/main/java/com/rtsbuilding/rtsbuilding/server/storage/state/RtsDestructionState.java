package com.rtsbuilding.rtsbuilding.server.storage.state;

import com.rtsbuilding.rtsbuilding.server.service.destruction.RtsDestructionBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 远程范围破坏（AREA_DESTROY）的可变状态容器。
 *
 * <p>从 RtsStorageSession 提取，按"玩家如何执行批量范围破坏"
 * 的职责聚合。包含破坏批次作业队列和因工具耐久不足而挂起的作业队列。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li><b>纯数据容器</b>——不包含业务逻辑，仅持有 public mutable 字段</li>
 *   <li><b>可独立实例化</b>——便于测试破坏状态切换而无需完整 session</li>
 * </ul>
 *
 * <p>此状态与 {@link RtsMiningState} 解耦，仅管理 AREA_DESTROY 的异步队列。
 * ULTIMINE 和 AREA_MINE 仍使用原有的 {@link RtsMiningState} 状态机。</p>
 */
public class RtsDestructionState {

    // ======================================================================
    // 破坏队列
    //      尚未执行的范围破坏批次。
    //      DestructionJob 类型定义在 RtsDestructionBatch 中。
    // ======================================================================

    /** 待处理的破坏批次作业队列 */
    public final Deque<RtsDestructionBatch.DestructionJob> destroyJobs = new ArrayDeque<>();

    /**
     * 因工具耐久不足而被挂起的破坏作业队列。
     * 玩家修复或更换工具后可通过恢复操作移回 destroyJobs 继续执行。
     */
    public final Deque<RtsDestructionBatch.DestructionJob> pendingDestroyJobs = new ArrayDeque<>();
}
