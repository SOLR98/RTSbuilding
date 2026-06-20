package com.rtsbuilding.rtsbuilding.server.loadout;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.OptionalInt;

/**
 * 玩家挖掘装备栏状态的持久化存储与查询工具类。
 * <p>
 * 通过玩家的持久数据（PersistentData）存储每个工具角色绑定的槽位及其物品指纹，
 * 用于在挖掘时快速定位玩家绑定的工具。
 */
public final class MiningLoadoutState {
    /** 玩家持久数据的根键名 */
    private static final String ROOT_KEY = "rtsbuilding";
    /** 挖掘装备栏数据的键名 */
    private static final String LOADOUT_KEY = "mining_loadout";

    /** 有效槽位最小值（快捷栏第一格） */
    private static final int MIN_SLOT = 0;
    /** 有效槽位最大值（背包最后一格） */
    private static final int MAX_SLOT = 35;

    /** 工具类，禁止实例化 */
    private MiningLoadoutState() {
    }

    /**
     * 获取指定角色绑定的工具槽位。
     *
     * @param player 目标玩家
     * @param role   要查询的工具角色
     * @return 如果找到有效的绑定槽位则返回其索引，否则返回空
     */
    public static OptionalInt getSlot(ServerPlayer player, MiningLoadoutRole role) {
        CompoundTag loadout = getLoadoutTag(player, false);
        if (loadout == null) {
            return OptionalInt.empty();
        }

        String key = roleKey(role);
        if (!loadout.contains(key)) {
            return OptionalInt.empty();
        }

        int slot = loadout.getInt(key);
        if (slot < MIN_SLOT || slot > MAX_SLOT) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(slot);
    }

    /**
     * 为指定角色绑定一个工具槽位，同时记录该槽位物品的指纹以检测后续变化。
     *
     * @param player 目标玩家
     * @param role   要绑定的工具角色
     * @param slot   要绑定的背包槽位索引
     * @return 如果槽位有效则返回 true，否则返回 false
     */
    public static boolean setSlot(ServerPlayer player, MiningLoadoutRole role, int slot) {
        if (slot < MIN_SLOT || slot > MAX_SLOT) {
            return false;
        }

        CompoundTag loadout = getLoadoutTag(player, true);
        String key = roleKey(role);
        loadout.putInt(key, slot);
        loadout.putString(fingerprintKey(role), stackFingerprint(player.getInventory().getItem(slot)));
        return true;
    }

    /**
     * 清除指定角色的绑定信息（槽位和指纹）。
     *
     * @param player 目标玩家
     * @param role   要清除绑定的工具角色
     */
    public static void clearSlot(ServerPlayer player, MiningLoadoutRole role) {
        CompoundTag loadout = getLoadoutTag(player, false);
        if (loadout == null) {
            return;
        }
        loadout.remove(roleKey(role));
        loadout.remove(fingerprintKey(role));
    }

    /**
     * 检查指定角色绑定的槽位中的物品是否仍然与记录的指纹匹配。
     * <p>
     * 用于判断玩家是否在绑定后替换了该槽位的工具。
     *
     * @param player 目标玩家
     * @param role   要检查的工具角色
     * @return 如果槽位物品与记录的指纹一致则返回 true，否则返回 false
     */
    public static boolean isStillMatching(ServerPlayer player, MiningLoadoutRole role) {
        OptionalInt slotOpt = getSlot(player, role);
        if (slotOpt.isEmpty()) {
            return false;
        }

        CompoundTag loadout = getLoadoutTag(player, false);
        if (loadout == null || !loadout.contains(fingerprintKey(role))) {
            return false;
        }

        String expected = loadout.getString(fingerprintKey(role));
        String current = stackFingerprint(player.getInventory().getItem(slotOpt.getAsInt()));
        return expected.equals(current);
    }

    /**
     * 获取指定角色绑定的槽位中的物品堆。
     *
     * @param player 目标玩家
     * @param role   要查询的工具角色
     * @return 绑定槽位的物品堆，如果未绑定则为空物品堆
     */
    public static ItemStack getAssignedStack(ServerPlayer player, MiningLoadoutRole role) {
        OptionalInt slot = getSlot(player, role);
        if (slot.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(slot.getAsInt());
    }

    /**
     * 生成物品堆的指纹字符串，用于追踪物品是否发生变化。
     * <p>
     * 指纹由物品的注册 ID 和耐久度组成，如 "minecraft:diamond_pickaxe:0"。
     *
     * @param stack 要生成指纹的物品堆
     * @return 指纹字符串，空物品堆返回空字符串
     */
    private static String stackFingerprint(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id + ":" + stack.getDamageValue();
    }

    /**
     * 获取角色对应的 NBT 键名（角色名全小写）。
     */
    private static String roleKey(MiningLoadoutRole role) {
        return role.name().toLowerCase();
    }

    /**
     * 获取角色指纹对应的 NBT 键名（角色键名 + "_fp" 后缀）。
     */
    private static String fingerprintKey(MiningLoadoutRole role) {
        return roleKey(role) + "_fp";
    }

    /**
     * 获取玩家的装备栏 NBT 标签。
     * <p>
     * 如果 {@code create} 为 true 且标签不存在，则会自动创建所需的所有层级。
     *
     * @param player 目标玩家
     * @param create 如果标签不存在时是否创建
     * @return 装备栏的 CompoundTag，如果不存在且不创建则返回 null
     */
    private static CompoundTag getLoadoutTag(ServerPlayer player, boolean create) {
        CompoundTag persistent = player.getPersistentData();
        CompoundTag root;
        if (persistent.contains(ROOT_KEY)) {
            root = persistent.getCompound(ROOT_KEY);
        } else if (create) {
            root = new CompoundTag();
            persistent.put(ROOT_KEY, root);
        } else {
            return null;
        }

        if (root.contains(LOADOUT_KEY)) {
            return root.getCompound(LOADOUT_KEY);
        }
        if (!create) {
            return null;
        }

        CompoundTag loadout = new CompoundTag();
        root.put(LOADOUT_KEY, loadout);
        return loadout;
    }
}

