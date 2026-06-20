package com.rtsbuilding.rtsbuilding.server.service.destruction;

import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.storage.cache.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.model.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;

public final class RtsDestroyBuffer {

    /** itemId → (nbtHash → count) */
    private final Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
    /** itemId → (nbtHash → prototype) */
    private final Map<String, Map<String, ItemStack>> prototypes = new LinkedHashMap<>();
    /** itemId → 首个 nbtHash，快速路径避免迭代器 */
    private final Map<String, String> firstHash = new HashMap<>();

    private static String nbtHash(ItemStack stack) {
        DataComponentMap cm = stack.getComponents();
        if (cm.isEmpty()) return "0";
        return Integer.toHexString(cm.hashCode());
    }

    public void addDrop(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String hash = nbtHash(stack);
        counts.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).merge(hash, (long) stack.getCount(), Long::sum);
        if (!firstHash.containsKey(itemId)) firstHash.put(itemId, hash);
        // 只在首次出现时保存原型副本
        prototypes.computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                .putIfAbsent(hash, stack.copyWithCount(1));
    }

    public boolean isEmpty() { return counts.isEmpty(); }

    public void flush(ServerPlayer player, RtsStorageSession session) {
        if (counts.isEmpty()) return;

        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> handlers = RtsLinkedStorageResolver.itemHandlersForInsert(linked);
        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);

        for (var outerEntry : new ArrayList<>(counts.entrySet())) {
            String itemId = outerEntry.getKey();
            Map<String, ItemStack> protoMap = prototypes.get(itemId);
            for (var innerEntry : new ArrayList<>(outerEntry.getValue().entrySet())) {
                String hash = innerEntry.getKey();
                long total = innerEntry.getValue();
                if (total <= 0) continue;
                ItemStack proto = protoMap != null ? protoMap.get(hash) : null;
                if (proto == null || proto.isEmpty()) continue;
                int maxSize = proto.getMaxStackSize();
                while (total > 0) {
                    int batch = (int) Math.min(total, maxSize);
                    ItemStack batchStack = proto.copyWithCount(batch);
                    ItemStack remain = aggregate != null && !aggregate.isEmpty()
                            ? aggregate.insert(batchStack, false)
                            : handlers.isEmpty() ? batchStack.copy()
                                    : RtsTransferInserter.storeToLinkedOnly(handlers, batchStack);
                    if (!remain.isEmpty()) remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, remain);
                    if (!remain.isEmpty()) player.drop(remain, false);
                    total -= batch;
                }
            }
        }
        counts.clear();
        prototypes.clear();
        firstHash.clear();
    }
}
