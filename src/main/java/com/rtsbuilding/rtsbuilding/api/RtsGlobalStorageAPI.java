package com.rtsbuilding.rtsbuilding.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 全局共享存储 API — 跨所有在线玩家链接存储的聚合查询与操作。
 *
 * <p>附属模组和外部系统可通过此接口查询所有玩家的整体存储状态，
 * 无需了解 RTS 内部实现细节。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 *   RtsAPI api = RtsAPI.get();
 *
 *   // 统计全区有多少钻石
 *   long totalDiamonds = api.globalStorage().countItemAcrossAllPlayers(level, Items.DIAMOND);
 *
 *   // 查找谁有橡木
 *   Map<String, Long> who = api.globalStorage().findPlayersWithItem(level,
 *       stack -> stack.is(Items.OAK_LOG));
 *
 *   // 从任意玩家提取物品
 *   ItemStack planks = api.globalStorage().extractFromAnyPlayer(level, Items.OAK_PLANKS, 32);
 * }</pre>
 */
public interface RtsGlobalStorageAPI {

    /**
     * 跨所有在线玩家统计匹配物品的总数。
     *
     * @param level     目标维度（用于解析玩家实例）
     * @param predicate 物品匹配条件
     * @return 汇总的匹配物品总数
     */
    long countItemsMatchingAllPlayers(ServerLevel level, Predicate<ItemStack> predicate);

    /**
     * 统计指定物品在所有玩家存储中的总数量。
     */
    long countItemAcrossAllPlayers(ServerLevel level, Item item);

    /**
     * 查找哪些玩家拥有匹配物品，返回玩家名称 → 匹配数量。
     */
    Map<String, Long> findPlayersWithItem(ServerLevel level, Predicate<ItemStack> predicate);

    /**
     * 获取全局存储合并汇总 (itemId → 总数量)。
     */
    void getGlobalSummary(ServerLevel level, Map<String, Long> out);

    /**
     * 从任意在线玩家存储中提取指定物品。
     *
     * @param level  目标维度
     * @param item   目标物品
     * @param amount 期望数量
     * @return 实际提取到的物品，无库存返回 ItemStack.EMPTY
     */
    ItemStack extractFromAnyPlayer(ServerLevel level, Item item, int amount);

    /**
     * 向任意在线玩家存储中插入物品。
     *
     * @param level 目标维度
     * @param stack 要插入的物品
     * @return 剩余无法存入的物品
     */
    ItemStack insertToAnyPlayer(ServerLevel level, ItemStack stack);

    /**
     * 获取拥有活跃 RTS 存储的在线玩家 UUID 集合。
     */
    Set<UUID> getActiveStoragePlayers(ServerLevel level);

    /**
     * 获取指定玩家的存储内容概要。
     */
    Map<String, Long> getPlayerStorage(UUID playerUuid, ServerLevel level);
}
