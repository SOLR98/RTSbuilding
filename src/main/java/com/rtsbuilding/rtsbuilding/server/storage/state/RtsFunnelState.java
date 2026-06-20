package com.rtsbuilding.rtsbuilding.server.storage.state;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 掉落物漏斗运行时状态的可变状态容器。
 *
 * <p>漏斗缓冲区使用 {@link LinkedHashMap} 以物品注册 ID 为键，
 * 天然归并同类物品，消除原先 {@code List<ItemStack>} 导致的
 * 重复条目和需要额外 {@code summarizeFunnelBuffer()} 的烦恼。
 */
public class RtsFunnelState {

    /** 漏斗模式是否激活 */
    public boolean funnelEnabled;

    /** 漏斗输出目标坐标 */
    public BlockPos funnelTarget;

    /** 漏斗冷却刻数 */
    public int funnelTickCooldown;

    /** 漏斗临时缓冲区——物品注册 ID → 归并后的 ItemStack */
    public final Map<String, ItemStack> funnelBuffer = new LinkedHashMap<>();
}
