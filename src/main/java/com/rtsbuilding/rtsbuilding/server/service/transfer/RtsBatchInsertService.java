package com.rtsbuilding.rtsbuilding.server.service.transfer;

import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.storage.OverflowOutcome;
import com.rtsbuilding.rtsbuilding.server.storage.RtsAggregateStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 批量物品插入协调服务。
 *
 * <p>负责将多物品的插入操作收集后一次性提交给 {@link RtsAggregateStorage}，
 * 一次路由构建覆盖所有物品类型，避免逐物品O(handlers)全遍历。
 * 处理溢出时的三级保护：存储→玩家背包→世界掉落。
 */
public final class RtsBatchInsertService {

    private RtsBatchInsertService() {
    }

    /**
     * 将一批物品插入玩家的链接存储。内部调用 aggregate.batchInsert()。
     *
     * @param player    目标玩家
     * @param aggregate 玩家的聚合存储实例
     * @param items     物品ID → 物品堆
     * @return 未完全存入的物品及其剩余数量
     */
    public static Map<String, ItemStack> batchInsertLinked(
            ServerPlayer player,
            RtsAggregateStorage aggregate,
            Map<String, ItemStack> items) {

        if (aggregate == null || aggregate.isEmpty())
            return items != null ? items : Map.of();
        if (items == null || items.isEmpty())
            return Map.of();

        return aggregate.batchInsert(items, false);
    }

    /**
     * 批量插入 + 三级溢出保护 (存储→背包→掉落)。
     *
     * @param player    目标玩家
     * @param aggregate 玩家的聚合存储实例
     * @param items     物品ID → 物品堆
     * @return 溢出结果 (移入背包的数量 + 掉落的数量)
     */
    public static OverflowOutcome batchInsertWithFallback(
            ServerPlayer player,
            RtsAggregateStorage aggregate,
            Map<String, ItemStack> items) {

        if (items == null || items.isEmpty()) {
            return OverflowOutcome.EMPTY;
        }

        // 一级: 批量插入链接存储
        Map<String, ItemStack> overflow;
        if (aggregate == null || aggregate.isEmpty()) {
            overflow = new HashMap<>(items);
        } else {
            overflow = aggregate.batchInsert(items, false);
        }

        if (overflow.isEmpty()) {
            RtsStorageTickService.INSTANCE.alert(player.getUUID());
            return OverflowOutcome.EMPTY;
        }

        // 二级: 玩家背包
        int movedToInventory = 0;
        Map<String, ItemStack> stillOverflow = new HashMap<>();
        for (var entry : overflow.entrySet()) {
            ItemStack stack = entry.getValue().copy();
            if (stack.isEmpty())
                continue;
            int before = stack.getCount();
            player.getInventory().add(stack);
            movedToInventory += (before - stack.getCount());
            if (!stack.isEmpty()) {
                stillOverflow.put(entry.getKey(), stack);
            }
        }

        // 三级: 世界掉落
        int dropped = 0;
        for (var entry : stillOverflow.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty())
                continue;
            dropped += entry.getValue().getCount();
            player.drop(entry.getValue(), false);
        }

        RtsStorageTickService.INSTANCE.alert(player.getUUID());
        return new OverflowOutcome(movedToInventory, dropped);
    }

    /**
     * 单物品插入 + 三级溢出保护的便捷方法。
     */
    public static OverflowOutcome insertOneWithFallback(
            ServerPlayer player,
            RtsAggregateStorage aggregate,
            ItemStack stack) {

        if (stack == null || stack.isEmpty()) {
            return OverflowOutcome.EMPTY;
        }

        Map<String, ItemStack> single = Map.of(stack.getItem().toString(), stack);
        return batchInsertWithFallback(player, aggregate, single);
    }
}
