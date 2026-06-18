package com.rtsbuilding.rtsbuilding.server.service;

/**
 * 搁置放置作业的扫描结果。
 * <p>
 * 记录在玩家点击重启按钮后，服务端对 pending job 剩余位置进行世界扫描
 * 后得到的各项统计数据，用于客户端面板展示和后续重启策略决策。
 *
 * @param itemId             正在放置的物品 ID（如 "minecraft:diamond_block"）
 * @param itemLabel          物品的显示名称（可选，为空时客户端使用 itemId）
 * @param totalRemaining     作业剩余总位置数（含已放置和冲突的）
 * @param alreadyPlacedCount 范围内已经存在同种方块的位置数（用户手动放置的）
 * @param conflictCount      范围内存在不同方块的位置数（冲突格）
 * @param availableItems     当前存储系统中可用的物品数量
 * @param neededItems        重启实际需要从存储中提取的物品数（totalRemaining - alreadyPlacedCount）
 * @param missingItems       缺少的物品数（neededItems - availableItems，负值表示足够）
 * @param workflowEntryId    目标工作流条目 ID
 */
public record RtsResumeScanResult(
        String itemId,
        String itemLabel,
        int totalRemaining,
        int alreadyPlacedCount,
        int conflictCount,
        long availableItems,
        int neededItems,
        long missingItems,
        int workflowEntryId) {

    /**
     * 返回是否物品充足（没有缺少）。
     */
    public boolean hasEnoughItems() {
        return missingItems <= 0;
    }

    /**
     * 返回是否存在冲突方块。
     */
    public boolean hasConflicts() {
        return conflictCount > 0;
    }

    /**
     * 返回实际需要放置的数量（已扣除已存在的同种方块，但未扣除库存）。
     */
    public int effectivePlaceCount() {
        return totalRemaining - alreadyPlacedCount;
    }
}
