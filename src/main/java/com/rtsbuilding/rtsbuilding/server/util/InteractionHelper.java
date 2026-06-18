package com.rtsbuilding.rtsbuilding.server.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 远程交互辅助工具集。
 *
 * <p>所有方法均假定目标物品<b>已在玩家主手上</b>（由客户端提前通过
 * {@code pickupLinkedToCarried} 或工具栏格选取放入），不做任何临时交换。
 * 交互结果（消耗、耐久损失、NBT 变化）由原版自然处理，物品剩余在主手上
 * 或自然消失——调用方负责在交互后处理主手上的剩余物品。
 */
public final class InteractionHelper {

    private InteractionHelper() {
    }

    // ======================================================================
    //  方块交互 —— 物品已在主手上
    // ======================================================================

    /**
     * 使用已存在于玩家主手上的物品对指定方块位置执行右键交互。
     * 物品的消耗、损坏或转换由原版逻辑自然处理——剩余的物品保留在主手上。
     */
    public static TemporaryContextSwitcher.UseOnOutcome useMainHandItemOnBlock(
            ServerPlayer player, ServerLevel level, BlockHitResult hit, boolean forceSecondaryUse) {
        InteractionResult result = TemporaryContextSwitcher.withTemporaryShiftKey(
                player, forceSecondaryUse,
                () -> player.gameMode.useItemOn(
                        player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit));
        return new TemporaryContextSwitcher.UseOnOutcome(result, player.getMainHandItem().copy());
    }

    /**
     * 使用已存在于玩家主手上的物品在空中使用。
     */
    public static TemporaryContextSwitcher.UseOnOutcome useMainHandItemInAir(
            ServerPlayer player, ServerLevel level, boolean forceSecondaryUse) {
        InteractionResult result = TemporaryContextSwitcher.withTemporaryShiftKey(
                player, forceSecondaryUse,
                () -> player.gameMode.useItem(
                        player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND));
        return new TemporaryContextSwitcher.UseOnOutcome(result, player.getMainHandItem().copy());
    }

    // ======================================================================
    //  实体交互 —— 物品已在主手上
    // ======================================================================

    /**
     * 使用已存在于玩家主手上的物品与实体交互。
     * 依次尝试 {@code player.interactOn} → {@code entity.interactAt} → {@code useItem}。
     * 交互后主手上剩余的物品由调用方负责处理（客户端通常发送
     * {@code returnCarriedToLinked} 将其存回关联存储）。
     */
    public static TemporaryContextSwitcher.UseOnOutcome useMainHandItemOnEntity(
            ServerPlayer player, ServerLevel level, Entity entity, Vec3 hit) {
        InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) {
            Vec3 localHit = hit.subtract(entity.position());
            result = entity.interactAt(player, localHit, InteractionHand.MAIN_HAND);
        }
        if (!result.consumesAction()) {
            result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
        }
        return new TemporaryContextSwitcher.UseOnOutcome(result, player.getMainHandItem().copy());
    }

    // ======================================================================
    //  交互位置解析
    // ======================================================================

    /**
     * 解析远程交互的"虚拟玩家脚部位置"：
     * <ul>
     *   <li>对实体：从实体中心向击中点反方向偏移 1.8 格</li>
     *   <li>对方块：从击中点沿法线反方向偏移 2.2 格</li>
     *   <li>无目标：返回 {@code hit} 原值</li>
     * </ul>
     */
    public static Vec3 resolveInteractionPosition(Entity targetEntity, BlockHitResult blockHit, Vec3 hit) {
        if (targetEntity != null) {
            Vec3 center = targetEntity.getBoundingBox().getCenter();
            Vec3 delta = center.subtract(hit);
            if (delta.lengthSqr() < 1.0e-6D) {
                delta = new Vec3(0.0D, 0.0D, 1.0D);
            }
            Vec3 at = center.subtract(delta.normalize().scale(1.8D));
            return new Vec3(at.x, at.y + 0.2D, at.z);
        }
        if (blockHit != null) {
            Vec3 n = Vec3.atLowerCornerOf(blockHit.getDirection().getNormal());
            Vec3 at = blockHit.getLocation().subtract(n.scale(2.2D));
            return new Vec3(at.x, at.y + 1.1D, at.z);
        }
        return hit;
    }

    // ======================================================================
    //  兼容方法：临时交换模式（供空手交互和放置执行器使用）
    //  新代码优先使用上面的 useMainHandItemOn* 系列
    // ======================================================================

    @Deprecated
    public static TemporaryContextSwitcher.UseOnOutcome useItemOnWithMainHand(ServerPlayer player, ServerLevel level,
            ItemStack handStack, BlockHitResult hit, boolean forceSecondaryUse) {
        ItemStack previousMainHand = player.getMainHandItem().copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, handStack);
        InteractionResult result;
        ItemStack remainder;
        try {
            result = TemporaryContextSwitcher.withTemporaryShiftKey(player, forceSecondaryUse,
                    () -> player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit));
        } finally {
            remainder = player.getMainHandItem().copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand);
        }
        return new TemporaryContextSwitcher.UseOnOutcome(result, remainder);
    }

    @Deprecated
    public static TemporaryContextSwitcher.UseOnOutcome useItemWithMainHand(ServerPlayer player, ServerLevel level,
            ItemStack handStack, boolean forceSecondaryUse) {
        ItemStack previousMainHand = player.getMainHandItem().copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, handStack);
        InteractionResult result;
        ItemStack remainder;
        try {
            result = TemporaryContextSwitcher.withTemporaryShiftKey(player, forceSecondaryUse,
                    () -> player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND));
        } finally {
            remainder = player.getMainHandItem().copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand);
        }
        return new TemporaryContextSwitcher.UseOnOutcome(result, remainder);
    }

    @Deprecated
    public static TemporaryContextSwitcher.UseOnOutcome useItemOnEntityWithMainHand(ServerPlayer player, ServerLevel level,
            ItemStack handStack, Entity entity, Vec3 hit) {
        ItemStack previousMainHand = player.getMainHandItem().copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, handStack);
        InteractionResult result;
        ItemStack remainder;
        try {
            result = player.interactOn(entity, InteractionHand.MAIN_HAND);
            if (!result.consumesAction()) {
                Vec3 localHit = hit.subtract(entity.position());
                result = entity.interactAt(player, localHit, InteractionHand.MAIN_HAND);
            }
            if (!result.consumesAction()) {
                result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
            }
        } finally {
            remainder = player.getMainHandItem().copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, previousMainHand);
        }
        return new TemporaryContextSwitcher.UseOnOutcome(result, remainder);
    }

    /**
     * 对实体执行交互操作（物品已在主手上）：先调 player.interactOn，再试 entity.interactAt，最后 fallback 到 useItem。
     */
    public static InteractionResult interactEntityWithMainHand(ServerPlayer player, ServerLevel level, Entity entity, Vec3 hit) {
        InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) {
            Vec3 localHit = hit.subtract(entity.position());
            result = entity.interactAt(player, localHit, InteractionHand.MAIN_HAND);
        }
        if (!result.consumesAction()) {
            result = player.gameMode.useItem(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND);
        }
        return result;
    }
}
