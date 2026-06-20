package com.rtsbuilding.rtsbuilding.server.storage;

import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.service.ServiceRegistry;
import com.rtsbuilding.rtsbuilding.server.storage.cache.RtsAggregateStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 存储核心操作 API — 对 {@link RtsAggregateStorage} 的统一存取查门面。
 *
 * <p>所有操作均通过当前玩家的聚合存储实例执行。聚合存储内部包含：
 * <ul>
 *   <li>路由计划 — 插入/提取时 O(1) 查表，无需全量扫描 handler</li>
 *   <li>插入缓存 — itemId → 最优目标 handler，O(1) 热路径</li>
 *   <li>itemToSlots 索引 — 提取时 O(1) 槽位跳转</li>
 *   <li>重入保护 — 并发安全，未存入物品不会丢失</li>
 * </ul>
 *
 * <h3>快速使用</h3>
 * <pre>{@code
 *   // 查询
 *   long count = StorageOperations.count(player, Items.DIRT);
 *   boolean has = StorageOperations.has(player, Items.STONE);
 *   Map<String, Long> all = StorageOperations.getAll(player);
 *
 *   // 插入
 *   ItemStack overflow = StorageOperations.insert(player, new ItemStack(Items.DIRT, 64));
 *
 *   // 提取
 *   ItemStack extracted = StorageOperations.extract(player, Items.OAK_PLANKS, 32);
 * }</pre>
 */
public final class StorageOperations {

    private StorageOperations() {
    }

    // ======================================================================
    //  查询 (Query)
    // ======================================================================

    /**
     * 查询链接存储中指定物品的总数量。
     *
     * @return 物品总数，若玩家无活跃存储返回 0
     */
    public static long count(ServerPlayer player, Item item) {
        if (player == null || item == null) return 0L;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        return storage != null ? storage.getTotalCount(item) : 0L;
    }

    /**
     * 查询链接存储中是否拥有指定物品。
     */
    public static boolean has(ServerPlayer player, Item item) {
        if (player == null || item == null) return false;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        return storage != null && storage.hasItem(item);
    }

    /**
     * 获取链接存储中所有物品的 itemId → 数量 映射。
     *
     * @return 非 null 的计数映射，无存储时返回空 Map
     */
    public static Map<String, Long> getAll(ServerPlayer player) {
        Map<String, Long> counts = new HashMap<>();
        if (player == null) return counts;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage != null) {
            storage.getAvailableItems(counts);
        }
        return counts;
    }

    /**
     * 获取链接存储中所有物品的计数和代表性原型。
     *
     * @param counts 输出参数，填充 itemId → 数量
     * @param protos 输出参数，填充 itemId → 代表性 ItemStack (含 NBT)
     */
    public static void getAllWithPrototypes(ServerPlayer player, Map<String, Long> counts, Map<String, ItemStack> protos) {
        if (player == null) return;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage != null) {
            storage.collectAllItems(counts, protos);
        }
    }

    /**
     * 获取指定 itemId 的代表性原型堆叠（数量=1，含完整 NBT 组件）。
     *
     * @return 原型 ItemStack，未找到返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack getPrototype(ServerPlayer player, String itemId) {
        if (player == null || itemId == null) return ItemStack.EMPTY;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        return storage != null ? storage.getPrototype(itemId) : ItemStack.EMPTY;
    }

    // ======================================================================
    //  插入 (Insert)
    // ======================================================================

    /**
     * 向链接存储中插入物品堆叠。
     * 优先存入已有该物品的容器（避免分散），其次按优先级。
     *
     * @param stack  要插入的物品堆叠
     * @return 无法存入的剩余物品，全部存入返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack insert(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage == null || storage.isEmpty()) return stack.copy();
        ItemStack result = storage.executeInsertRoute(stack, false);
        if (result.isEmpty()) {
            RtsStorageTickService.INSTANCE.alert(player.getUUID());
        }
        return result;
    }

    /**
     * 模拟插入，不实际修改存储。
     *
     * @return 模拟的剩余物品，全部可存入返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack insertSimulate(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage == null || storage.isEmpty()) return stack.copy();
        return storage.executeInsertRoute(stack, true);
    }

    /**
     * 批量插入多种物品。一次路由查表覆盖所有物品类型。
     *
     * @param items itemId → ItemStack 映射
     * @return 未能完全存入的溢出 (itemId → 剩余堆叠)，全部存入返回空 Map
     */
    public static Map<String, ItemStack> batchInsert(ServerPlayer player, Map<String, ItemStack> items) {
        if (player == null || items == null || items.isEmpty()) return Map.of();
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage == null || storage.isEmpty()) {
            return new HashMap<>(items);
        }
        Map<String, ItemStack> overflow = storage.batchInsert(items, false);
        if (overflow.isEmpty() || overflow.size() < items.size()) {
            RtsStorageTickService.INSTANCE.alert(player.getUUID());
        }
        return overflow;
    }

    // ======================================================================
    //  提取 (Extract)
    // ======================================================================

    /**
     * 从链接存储中提取指定物品。低优先级容器优先被取走。
     * 基于预计算提取路由计划，只扫描真正持有该物品的 handler。
     *
     * @param item   要提取的物品类型
     * @param amount 期望提取的最大数量
     * @return 实际提取到的物品堆叠，无库存返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack extract(ServerPlayer player, Item item, int amount) {
        if (player == null || item == null || amount <= 0) return ItemStack.EMPTY;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage == null || storage.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = storage.executeExtractRoute(item, null, amount);
        if (!result.isEmpty()) {
            RtsStorageTickService.INSTANCE.alert(player.getUUID());
        }
        return result;
    }

    /**
     * 从链接存储中提取匹配指定原型的物品（NBT 组件精确匹配）。
     *
     * @param prototype 目标原型堆叠（用于 NBT 组件匹配）
     * @param amount    期望提取的最大数量
     * @return 实际提取到的物品堆叠
     */
    public static ItemStack extractMatching(ServerPlayer player, ItemStack prototype, int amount) {
        if (player == null || prototype == null || prototype.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage == null || storage.isEmpty()) return ItemStack.EMPTY;
        ItemStack result = storage.extractMatchingPrototype(prototype, amount);
        if (!result.isEmpty()) {
            RtsStorageTickService.INSTANCE.alert(player.getUUID());
        }
        return result;
    }

    // ======================================================================
    //  便利方法 (Convenience)
    // ======================================================================

    /**
     * 查询链接存储是否为空（没有挂载任何 handler）。
     */
    public static boolean isEmpty(ServerPlayer player) {
        if (player == null) return true;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        return storage == null || storage.isEmpty();
    }

    /**
     * 获取指定 itemId 在链接存储中的总数量。
     */
    public static long countByItemId(ServerPlayer player, String itemId) {
        if (player == null || itemId == null) return 0L;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage == null) return 0L;
        long total = 0L;
        // 使用 getAvailableItems 会遍历所有 handler，再从中提取目标 itemId
        Map<String, Long> tmp = new HashMap<>();
        storage.getAvailableItems(tmp);
        return tmp.getOrDefault(itemId, 0L);
    }

    // ======================================================================
    //  全局共享存储 (跨所有在线玩家)
    // ======================================================================

    /**
     * 跨所有在线玩家统计指定物品的总数。
     */
    public static long globalCount(ServerLevel level, Item item) {
        return ServiceRegistry.getInstance().globalStorage().countItemAcrossAllPlayers(level, item);
    }

    /**
     * 跨所有在线玩家统计匹配物品的总数。
     */
    public static long globalCountMatching(ServerLevel level, Predicate<ItemStack> predicate) {
        return ServiceRegistry.getInstance().globalStorage().countAcrossAllPlayers(level, predicate);
    }

    /**
     * 查找哪些玩家拥有匹配物品。
     *
     * @return 玩家名称 → 匹配数量 映射
     */
    public static Map<String, Long> globalFindPlayers(ServerLevel level, Predicate<ItemStack> predicate) {
        return ServiceRegistry.getInstance().globalStorage().findPlayersWithItem(level, predicate);
    }

    /**
     * 获取全局存储合并汇总 (itemId → 总数量)。
     */
    public static Map<String, Long> globalSummary(ServerLevel level) {
        Map<String, Long> counts = new HashMap<>();
        ServiceRegistry.getInstance().globalStorage().getGlobalSummary(level, counts);
        return counts;
    }

    /**
     * 从任意在线玩家存储中提取物品。
     */
    public static ItemStack globalExtract(ServerLevel level, Item item, int amount) {
        return ServiceRegistry.getInstance().globalStorage().extractFromAnyPlayer(level, item, amount);
    }

    /**
     * 向任意在线玩家存储中插入物品。
     */
    public static ItemStack globalInsert(ServerLevel level, ItemStack stack) {
        return ServiceRegistry.getInstance().globalStorage().insertToAnyPlayer(level, stack);
    }

    /**
     * 获取拥有活跃 RTS 存储的在线玩家 UUID 集合。
     */
    public static Set<UUID> globalActivePlayers(ServerLevel level) {
        return ServiceRegistry.getInstance().globalStorage().getActiveStoragePlayers(level);
    }
}
