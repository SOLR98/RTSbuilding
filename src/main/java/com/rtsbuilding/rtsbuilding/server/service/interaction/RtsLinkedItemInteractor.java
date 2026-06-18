package com.rtsbuilding.rtsbuilding.server.service.interaction;

import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import com.rtsbuilding.rtsbuilding.server.util.InteractionHelper;
import com.rtsbuilding.rtsbuilding.server.util.TemporaryContextSwitcher;
import com.rtsbuilding.rtsbuilding.server.util.TemporaryContextSwitcher.RayContext;
import com.rtsbuilding.rtsbuilding.server.util.TemporaryContextSwitcher.UseOnOutcome;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 使用大头针物品进行 RTS 远程交互。
 *
 * <p>物品必须由客户端<b>在本方法调用前</b>放到玩家主手上。客户端负责物品挪移：
 * <ol>
 *   <li>旧主手物品 → 玩家背包（或关联存储）</li>
 *   <li>请求关联存储物品 → {@code pickupLinkedToCarried}</li>
 *   <li>发送交互载荷 → 本方法执行交互</li>
 *   <li>交互后如需归还 → 发送 {@code returnCarriedToLinked}</li>
 * </ol>
 *
 * <p>本类仅负责调度虚拟摄像机上下文并委托
 * {@link InteractionHelper}——不提取物品，也不退还余量。
 */
public final class RtsLinkedItemInteractor {

    private static final double REMOTE_POV_BLOCK_REACH = 4.0D;

    private RtsLinkedItemInteractor() {
    }

    /**
     * 使用玩家主手上已有的物品与目标方块或实体交互。
     * 调用方必须在调用前将大头针物品放入玩家主手。
     */
    public static InteractionResult interactWithLinkedItem(ServerPlayer player, ServerLevel level, RtsStorageSession session,
            Entity targetEntity, BlockHitResult blockHit, Vec3 hit, String itemId, RayContext rayContext) {
        if (itemId == null || itemId.isBlank()) {
            return InteractionResult.PASS;
        }
        if (!RtsLinkedStorageResolver.hasAnyStorage(player, session)) {
            return InteractionResult.PASS;
        }

        // 校验主手物品与大头针物品 ID 一致。客户端应在发送交互载荷前通过
        // pickupLinkedToCarried 将目标物品放好。
        ItemStack handItem = player.getMainHandItem();
        if (handItem.isEmpty()) {
            return InteractionResult.PASS;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return InteractionResult.PASS;
        }
        if (handItem.getItem() != BuiltInRegistries.ITEM.get(id)) {
            return InteractionResult.PASS;
        }

        Vec3 interactionPos = InteractionHelper.resolveInteractionPosition(targetEntity, blockHit, hit);
        UseOnOutcome outcome = TemporaryContextSwitcher.withTemporaryUseItemContext(
                player,
                interactionPos,
                hit,
                rayContext,
                REMOTE_POV_BLOCK_REACH,
                () -> {
            if (targetEntity != null) {
                return InteractionHelper.useMainHandItemOnEntity(player, level, targetEntity, hit);
            }
            // 对方块的四级回退：
            //   1. 普通 useItemOn（右键方块）
            //   2. 普通 useItem（右键空气）
            //   3. Shift useItemOn（Shift+右键方块，如对着交互方块放置实体方块）
            //   4. Shift useItem（Shift+右键空气）
            UseOnOutcome primaryOn = InteractionHelper.useMainHandItemOnBlock(player, level, blockHit, false);
            if (primaryOn.result().consumesAction()) {
                return primaryOn;
            }
            UseOnOutcome primaryUse = InteractionHelper.useMainHandItemInAir(player, level, false);
            if (primaryUse.result().consumesAction()) {
                return primaryUse;
            }
            UseOnOutcome secondaryOn = InteractionHelper.useMainHandItemOnBlock(player, level, blockHit, true);
            if (secondaryOn.result().consumesAction()) {
                return secondaryOn;
            }
            return InteractionHelper.useMainHandItemInAir(player, level, true);
                });

        RtsStorageTickService.INSTANCE.forceRefresh(player);
        session.transfer.pageDataVersion.incrementAndGet();
        return outcome.result();
    }
}
