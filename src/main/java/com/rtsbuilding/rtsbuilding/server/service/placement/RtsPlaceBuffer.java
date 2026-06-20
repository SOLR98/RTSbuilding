package com.rtsbuilding.rtsbuilding.server.service.placement;

import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferExtractor;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.storage.cache.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;

public final class RtsPlaceBuffer {

    /** itemId → (nbtHash → count) */
    final Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
    /** itemId → (nbtHash → prototype)，与 counts 同键，消去 fullKey 字符串拼接 */
    final Map<String, Map<String, ItemStack>> prototypes = new LinkedHashMap<>();
    /** itemId → 首个(通常唯一) nbtHash，快速路径避免迭代器 */
    private final Map<String, String> firstHash = new HashMap<>();

    static String nbtHash(ItemStack stack) {
        DataComponentMap cm = stack.getComponents();
        if (cm.isEmpty()) return "0";
        return Integer.toHexString(cm.hashCode());
    }

    public int fillFromStorage(ServerPlayer player, RtsStorageSession session,
                                     Map<String, Integer> needed, Map<String, ItemStack> protos, int budget) {
        if (needed.isEmpty() || budget <= 0) return 0;

        boolean creativeSource = player.isCreative();
        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);

        // 延迟解析——aggregate 命中时完全跳过 handler 探测
        List<IItemHandler> extractHandlers = null;
        boolean includePlayerInv = false;
        boolean handlersResolved = false;

        int totalFilled = 0;
        for (var entry : new ArrayList<>(needed.entrySet())) {
            String itemId = entry.getKey();
            if (budget <= 0) break;
            int need = entry.getValue();
            if (need <= 0) continue;

            ResourceLocation id = ResourceLocation.tryParse(itemId);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            ItemStack prototype = protos.getOrDefault(itemId, ItemStack.EMPTY);

            int extractCount = Math.min(need, budget);
            ItemStack extracted;
            if (creativeSource) {
                extracted = new ItemStack(item, extractCount);
            } else if (aggregate != null && !aggregate.isEmpty()) {
                extracted = prototype.isEmpty() ? aggregate.extract(item, extractCount)
                        : aggregate.extractMatching(item, prototype, extractCount);
                // aggregate 未命中时延迟解析 handler 并回退
                if (extracted.isEmpty()) {
                    if (!handlersResolved) {
                        var linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
                        includePlayerInv = com.rtsbuilding.rtsbuilding.server.storage.RtsStoragePageBuilder
                                .shouldIncludePlayerMainInventoryInStorageView(player, session);
                        if (!linked.isEmpty()) extractHandlers = RtsLinkedStorageResolver.itemHandlersForExtract(linked);
                        handlersResolved = true;
                    }
                    if (extractHandlers != null && !extractHandlers.isEmpty()) {
                        extracted = RtsTransferExtractor.extractMatchingFromNetwork(
                                extractHandlers, player, item, prototype, extractCount);
                    } else if (includePlayerInv) {
                        extracted = RtsTransferExtractor.extractMatchingFromPlayerMainInventory(
                                player, item, prototype, extractCount);
                    }
                }
            } else {
                // 无 aggregate，首次命中时解析
                if (!handlersResolved) {
                    var linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
                    includePlayerInv = com.rtsbuilding.rtsbuilding.server.storage.RtsStoragePageBuilder
                            .shouldIncludePlayerMainInventoryInStorageView(player, session);
                    if (!linked.isEmpty()) extractHandlers = RtsLinkedStorageResolver.itemHandlersForExtract(linked);
                    handlersResolved = true;
                }
                if (extractHandlers != null && !extractHandlers.isEmpty()) {
                    extracted = RtsTransferExtractor.extractMatchingFromNetwork(
                            extractHandlers, player, item, prototype, extractCount);
                } else if (includePlayerInv) {
                    extracted = RtsTransferExtractor.extractMatchingFromPlayerMainInventory(
                            player, item, prototype, extractCount);
                } else {
                    continue;
                }
            }
            if (extracted.isEmpty()) continue;

            int got = extracted.getCount();
            String hash = nbtHash(extracted);
            counts.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).merge(hash, (long) got, Long::sum);
            if (!firstHash.containsKey(itemId)) firstHash.put(itemId, hash);
            prototypes.computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                    .putIfAbsent(hash, extracted.copyWithCount(1));

            int remaining = need - got;
            if (remaining <= 0) needed.remove(itemId);
            else needed.put(itemId, remaining);
            totalFilled += got;
            budget = Math.max(0, budget - got);
        }
        return totalFilled;
    }

    public ItemStack takeOne(String itemId, ItemStack preferredStack) {
        if (itemId == null) return ItemStack.EMPTY;

        // 快速路径：单变体 → 缓存 hash，零迭代器
        String hash = firstHash.get(itemId);
        if (hash != null) return drainOne(itemId, hash);

        // 多变体
        Map<String, Long> variants = counts.get(itemId);
        if (variants == null || variants.isEmpty()) return ItemStack.EMPTY;
        hash = nbtHash(preferredStack);
        if (hash != null && variants.containsKey(hash)) return drainOne(itemId, hash);
        // 回退任意
        hash = variants.keySet().iterator().next();
        return drainOne(itemId, hash);
    }

    private ItemStack drainOne(String itemId, String hash) {
        Map<String, Long> variants = counts.get(itemId);
        if (variants == null) return ItemStack.EMPTY;
        Long c = variants.get(hash);
        if (c == null || c <= 0) return ItemStack.EMPTY;
        boolean lastOne = c <= 1;
        if (lastOne) {
            variants.remove(hash);
            if (variants.isEmpty()) {
                counts.remove(itemId);
                firstHash.remove(itemId);
            } else {
                firstHash.put(itemId, variants.keySet().iterator().next());
            }
        } else {
            variants.put(hash, c - 1);
        }
        Map<String, ItemStack> protoMap = prototypes.get(itemId);
        if (protoMap == null) return ItemStack.EMPTY;
        ItemStack proto = lastOne ? protoMap.remove(hash) : protoMap.get(hash);
        if (lastOne && protoMap.isEmpty()) prototypes.remove(itemId);
        return proto != null ? proto.copy() : ItemStack.EMPTY;
    }

    public void giveBack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (itemId == null) return;
        String hash = nbtHash(stack);
        counts.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).merge(hash, 1L, Long::sum);
        if (!firstHash.containsKey(itemId)) firstHash.put(itemId, hash);
        prototypes.computeIfAbsent(itemId, k -> new LinkedHashMap<>())
                .putIfAbsent(hash, stack.copyWithCount(1));
    }

    public void flush(ServerPlayer player, RtsStorageSession session) {
        if (isEmpty()) return;
        var linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> insertHandlers = RtsLinkedStorageResolver.itemHandlersForInsert(linked);
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
                            : RtsTransferInserter.storeToLinkedOnly(insertHandlers, batchStack);
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

    public boolean hasItems() {
        if (counts.isEmpty()) return false;
        for (var entry : counts.entrySet()) {
            for (long c : entry.getValue().values()) if (c > 0) return true;
        }
        return false;
    }

    public boolean isEmpty() { return counts.isEmpty(); }
}
