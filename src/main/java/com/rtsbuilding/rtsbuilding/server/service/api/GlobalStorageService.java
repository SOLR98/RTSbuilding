package com.rtsbuilding.rtsbuilding.server.service.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 全局共享存储服务 — 跨所有在线玩家链接存储的聚合查询与操作。
 *
 * <p>迭代 {@link SessionService#allSessions()} 遍历所有活跃 RTS 会话，
 * 聚合每个玩家的 {@code RtsAggregateStorage} 数据。
 *
 * <h3>与 StorageOperations 的区别</h3>
 * {@code StorageOperations} 操作单个玩家的链接存储，
 * 而 {@code GlobalStorageService} 是跨所有在线玩家的全局视图。
 */
public interface GlobalStorageService {

    /**
     * 跨所有在线玩家统计匹配物品的总数。
     *
     * @param level     目标服务器维度（用于解析玩家实例）
     * @param predicate 匹配条件
     * @return 所有玩家存储中匹配物品的汇总计数
     */
    long countAcrossAllPlayers(ServerLevel level, Predicate<ItemStack> predicate);

    /**
     * 统计指定物品在所有玩家存储中的总数量。
     */
    long countItemAcrossAllPlayers(ServerLevel level, Item item);

    /**
     * 查找哪些玩家拥有匹配物品，返回 playerName → matchingCount。
     * 用于 UI 展示"哪些玩家有钻石"等场景。
     *
     * @return 玩家名称到匹配物品数量的映射
     */
    Map<String, Long> findPlayersWithItem(ServerLevel level, Predicate<ItemStack> predicate);

    /**
     * 获取所有在线玩家存储的合并汇总。
     *
     * @param level  目标维度
     * @param counts 输出参数，填充 itemId → 总数（合并去重）
     */
    void getGlobalSummary(ServerLevel level, Map<String, Long> counts);

    /**
     * 从任意在线玩家存储中提取物品。
     * 遍历所有玩家，从第一个有库存的玩家提取。
     *
     * @param level  目标维度
     * @param item   目标物品
     * @param amount 期望提取的最大数量
     * @return 实际提取到的物品堆叠，无库存返回 ItemStack.EMPTY
     */
    ItemStack extractFromAnyPlayer(ServerLevel level, Item item, int amount);

    /**
     * 向任意在线玩家存储中插入物品。
     * 遍历所有玩家，尝试插入到第一个有空位的存储。
     *
     * @param level 目标维度
     * @param stack 要插入的物品堆叠
     * @return 无法存入的剩余物品，全部存入返回 ItemStack.EMPTY
     */
    ItemStack insertToAnyPlayer(ServerLevel level, ItemStack stack);

    /**
     * 获取当前拥有活跃 RTS 存储的在线玩家 UUID 集合。
     */
    Set<UUID> getActiveStoragePlayers(ServerLevel level);

    /**
     * 获取指定玩家的存储物品计数。
     *
     * @param playerUuid 玩家 UUID
     * @param level      用于解析 ServerPlayer
     * @return itemId → 数量的映射，玩家不在线或无存储返回空 Map
     */
    Map<String, Long> getPlayerStorage(UUID playerUuid, ServerLevel level);
}
