package com.rtsbuilding.rtsbuilding.server.storage.cache;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.compat.AnySlotInsertItemHandler;
import com.rtsbuilding.rtsbuilding.compat.RefreshableSnapshotHandler;
import com.rtsbuilding.rtsbuilding.compat.ReportedCountItemHandler;
import com.rtsbuilding.rtsbuilding.compat.ae2.RtsAe2Compat;
import com.rtsbuilding.rtsbuilding.compat.bd.RtsBdCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RtsHandlerCache {

    /** Cached slot snapshots: index → CachedSlot with full ItemStack. */
    private CachedSlot[] front = new CachedSlot[0];

    /** Accumulated counts keyed by canonical item ID. Thread-safe for concurrent reads. */
    private final Map<String, Long> countsByItem = new ConcurrentHashMap<>();

    /** Representative (count=1) stacks keyed by item ID, for exact-entry building. */
    private final Map<String, ItemStack> prototypeByItem = new ConcurrentHashMap<>();

    /** itemId → 该容器内持有该物品的槽位号列表。 */
    private final Map<String, List<Integer>> itemToSlots = new ConcurrentHashMap<>();

    /** 该容器当前的空槽位数。 */
    private int emptySlotCount;

    /** 该容器内存在的物品类型集合。用于插入路由快速判断。 */
    private final Set<String> hasItemType = ConcurrentHashMap.newKeySet();

    /** Whether the cache has been dirtied since last clear. */
    private volatile boolean dirtySinceLastRead;

    /** AE2 KeyCounter 上一帧快照 (itemId → count)。仅 AE2 处理器使用。 */
    private volatile Map<String, Long> ae2LastItemCounts;

    // ======================================================================
    //  Cache update — 委托给策略类选择合适路径
    // ======================================================================

    public Set<String> update(IItemHandler handler) {
        Objects.requireNonNull(handler, "handler");
        return CacheUpdateStrategy.select(handler).update(this, handler);
    }

    // ======================================================================
    //  Query API
    // ======================================================================

    public long getCount(Item item) {
        return this.countsByItem.getOrDefault(item.toString(), 0L);
    }

    public long getCount(String itemId) {
        return this.countsByItem.getOrDefault(itemId, 0L);
    }

    public void getAvailableItems(Map<String, Long> out) {
        for (var entry : this.countsByItem.entrySet()) {
            out.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
    }

    public ItemStack getPrototype(String itemId) {
        ItemStack stack = this.prototypeByItem.get(itemId);
        return stack != null ? stack.copy() : ItemStack.EMPTY;
    }

    public CachedSlot getSlot(int slot) {
        if (slot < 0 || slot >= this.front.length) {
            return CachedSlot.EMPTY;
        }
        CachedSlot entry = this.front[slot];
        return entry != null ? entry : CachedSlot.EMPTY;
    }

    public ItemStack getStackInSlot(int slot) {
        CachedSlot entry = getSlot(slot);
        return entry.isEmpty() ? ItemStack.EMPTY : entry.toItemStack();
    }

    public int getCachedSlotCount() {
        return this.front.length;
    }

    public boolean hasData() {
        return !this.countsByItem.isEmpty() || this.front.length > 0;
    }

    public List<Integer> getSlotsFor(String itemId) {
        List<Integer> slots = this.itemToSlots.get(itemId);
        return slots != null ? slots : List.of();
    }

    public boolean hasItemType(String itemId) {
        return this.hasItemType.contains(itemId);
    }

    public Set<String> getHasItemTypes() {
        return this.hasItemType.isEmpty() ? Set.of() : Set.copyOf(this.hasItemType);
    }

    public int getEmptySlotCount() {
        return this.emptySlotCount;
    }

    public boolean isDirty() {
        return this.dirtySinceLastRead;
    }

    public void clearDirty() {
        this.dirtySinceLastRead = false;
    }

    public void invalidate() {
        this.front = new CachedSlot[0];
        this.countsByItem.clear();
        this.prototypeByItem.clear();
        this.itemToSlots.clear();
        this.hasItemType.clear();
        this.emptySlotCount = 0;
        this.ae2LastItemCounts = null;
        this.dirtySinceLastRead = true;
    }

    public void release() {
        this.front = new CachedSlot[0];
        this.countsByItem.clear();
        this.prototypeByItem.clear();
        this.itemToSlots.clear();
        this.hasItemType.clear();
        this.emptySlotCount = 0;
        this.ae2LastItemCounts = null;
        this.dirtySinceLastRead = false;
    }

    // ======================================================================
    //  缓存更新策略 — 三路 dispatch
    // ======================================================================

    /**
     * 缓存更新策略接口——将 BD/AE2/通用三种路径解耦为独立策略类。
     */
    @FunctionalInterface
    interface CacheUpdateStrategy {
        Set<String> update(RtsHandlerCache cache, IItemHandler handler);

        static CacheUpdateStrategy select(IItemHandler handler) {
            if (handler instanceof RtsBdCompat.DirectExtractHandler) {
                return BdStrategy.INSTANCE;
            }
            if (handler instanceof ReportedCountItemHandler
                    && handler instanceof AnySlotInsertItemHandler
                    && handler instanceof RefreshableSnapshotHandler) {
                return Ae2Strategy.INSTANCE;
            }
            return ScanStrategy.INSTANCE;
        }
    }

    /** BD 网络快速路径——直接 diff countAndProtos API。 */
    enum BdStrategy implements CacheUpdateStrategy {
        INSTANCE;

        @Override
        public Set<String> update(RtsHandlerCache c, IItemHandler handler) {
            Set<String> changes = new HashSet<>();
            Map<String, Long> currentCounts = new HashMap<>();
            RtsBdCompat.getCurrentCountsAndProtos(handler, currentCounts, c.prototypeByItem);

            if (c.countsByItem.isEmpty()) {
                for (var entry : currentCounts.entrySet()) {
                    c.countsByItem.put(entry.getKey(), entry.getValue());
                    c.hasItemType.add(entry.getKey());
                    changes.add(entry.getKey());
                }
            } else {
                Set<String> seen = new HashSet<>();
                for (var entry : c.countsByItem.entrySet()) {
                    String id = entry.getKey();
                    long oldCount = entry.getValue();
                    long newCount = currentCounts.getOrDefault(id, 0L);
                    seen.add(id);
                    if (newCount != oldCount) {
                        changes.add(id);
                        if (newCount > 0L) {
                            c.countsByItem.put(id, newCount);
                        } else {
                            c.countsByItem.remove(id);
                            c.hasItemType.remove(id);
                            c.prototypeByItem.remove(id);
                        }
                    }
                }
                for (var entry : currentCounts.entrySet()) {
                    String id = entry.getKey();
                    if (!seen.contains(id)) {
                        changes.add(id);
                        c.countsByItem.put(id, entry.getValue());
                        c.hasItemType.add(id);
                    }
                }
            }
            if (!changes.isEmpty()) c.dirtySinceLastRead = true;
            return changes;
        }
    }

    /** AE2 快速路径——KeyCounter diff。 */
    enum Ae2Strategy implements CacheUpdateStrategy {
        INSTANCE;

        @Override
        public Set<String> update(RtsHandlerCache c, IItemHandler handler) {
            Set<String> changes = new HashSet<>();
            if (c.ae2LastItemCounts == null || c.ae2LastItemCounts.isEmpty()) {
                Map<String, Long> current = new ConcurrentHashMap<>();
                RtsAe2Compat.collectCountsAndPrototypes(handler, current, c.prototypeByItem);
                for (var entry : current.entrySet()) {
                    c.countsByItem.put(entry.getKey(), entry.getValue());
                    c.hasItemType.add(entry.getKey());
                    changes.add(entry.getKey());
                }
                c.ae2LastItemCounts = current;
            } else {
                Map<String, Long> current = RtsAe2Compat.getCurrentItemCounts(handler);
                for (var entry : c.ae2LastItemCounts.entrySet()) {
                    String id = entry.getKey();
                    long oldCount = entry.getValue();
                    long newCount = current.getOrDefault(id, 0L);
                    if (newCount != oldCount) {
                        changes.add(id);
                        if (newCount > 0L) {
                            c.countsByItem.put(id, newCount);
                        } else {
                            c.countsByItem.remove(id);
                            c.hasItemType.remove(id);
                        }
                    }
                }
                for (var entry : current.entrySet()) {
                    String id = entry.getKey();
                    if (!c.ae2LastItemCounts.containsKey(id)) {
                        long count = entry.getValue();
                        changes.add(id);
                        c.countsByItem.put(id, count);
                        c.hasItemType.add(id);
                        if (!c.prototypeByItem.containsKey(id)) {
                            ItemStack proto = RtsAe2Compat.getPrototypeFromHandler(handler, id);
                            if (!proto.isEmpty()) c.prototypeByItem.put(id, proto);
                        }
                    }
                }
                c.ae2LastItemCounts = current;
            }
            if (!changes.isEmpty()) c.dirtySinceLastRead = true;
            return changes;
        }
    }

    /** 通用路径——逐槽增量扫描。 */
    enum ScanStrategy implements CacheUpdateStrategy {
        INSTANCE;

        @Override
        public Set<String> update(RtsHandlerCache c, IItemHandler handler) {
            int slots = numSlots(handler);
            if (slots > c.front.length) c.front = Arrays.copyOf(c.front, slots);

            Set<String> changes = new HashSet<>();
            boolean skipNbtCompare = handler instanceof ReportedCountItemHandler;

            for (int slot = 0; slot < slots; slot++) {
                CachedSlot oldEntry = c.front[slot];
                CachedSlot newEntry = readSlot(handler, slot);
                c.front[slot] = newEntry;
                if (!hasChanged(oldEntry, newEntry, skipNbtCompare)) continue;

                if (oldEntry != null && !oldEntry.isEmpty()) {
                    changes.add(oldEntry.itemId());
                    applySlotDelta(c, oldEntry.itemId(), oldEntry.count, true, null, slot,
                            newEntry == null || newEntry.isEmpty());
                }
                if (newEntry != null && !newEntry.isEmpty()) {
                    changes.add(newEntry.itemId());
                    applySlotDelta(c, newEntry.itemId(), newEntry.count, false,
                            skipNbtCompare ? newEntry.fullStack : newEntry.toPrototype(), slot, false);
                }
            }

            if (c.emptySlotCount <= 0 && c.countsByItem.isEmpty()) {
                int count = 0;
                for (int s = 0; s < slots; s++) {
                    CachedSlot entry = c.front[s];
                    if (entry == null || entry.isEmpty()) count++;
                }
                c.emptySlotCount = count;
            }

            if (slots < c.front.length) {
                for (int slot = slots; slot < c.front.length; slot++) {
                    CachedSlot oldEntry = c.front[slot];
                    if (oldEntry != null && !oldEntry.isEmpty()) {
                        changes.add(oldEntry.itemId());
                        applySlotDelta(c, oldEntry.itemId(), oldEntry.count, true, null);
                    }
                    c.front[slot] = null;
                }
                c.front = Arrays.copyOf(c.front, slots);
            }
            if (!changes.isEmpty()) c.dirtySinceLastRead = true;
            return changes;
        }
    }

    // ======================================================================
    //  静态内部方法——供策略类共享使用
    // ======================================================================

    private static int numSlots(IItemHandler handler) {
        try {
            return handler.getSlots();
        } catch (Exception e) {
            RtsbuildingMod.LOGGER.warn("Failed to get slot count from handler {}: {}", handler.getClass().getName(), e.toString());
            return 0;
        }
    }

    private static CachedSlot readSlot(IItemHandler handler, int slot) {
        try {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) {
                return CachedSlot.EMPTY;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            long count = (handler instanceof ReportedCountItemHandler rc)
                    ? Math.max(0L, rc.getReportedCount(slot))
                    : stack.getCount();
            ItemStack stored = (handler instanceof ReportedCountItemHandler)
                    ? stack
                    : stack.copy();
            int nbtHash = stored.hashCode();
            return new CachedSlot(id.toString(), stack.getItem(), count, stored, nbtHash);
        } catch (Exception e) {
            RtsbuildingMod.LOGGER.warn("Failed to read slot {} from handler {}: {}", slot, handler.getClass().getName(), e.toString());
            return CachedSlot.EMPTY;
        }
    }

    private static boolean hasChanged(CachedSlot oldEntry, CachedSlot newEntry, boolean skipNbtCompare) {
        if (oldEntry == null && newEntry == null) return false;
        if (oldEntry == null || newEntry == null) return true;
        if (!oldEntry.itemId.equals(newEntry.itemId)) return true;
        if (oldEntry.count != newEntry.count) return true;
        if (!skipNbtCompare && oldEntry.count > 0 && newEntry.count > 0) {
            if (oldEntry.fullStack == newEntry.fullStack) return false;
            if (oldEntry.nbtHash != 0 && newEntry.nbtHash != 0 && oldEntry.nbtHash != newEntry.nbtHash) return true;
            if (!ItemStack.isSameItemSameComponents(oldEntry.fullStack, newEntry.fullStack)) return true;
        }
        return false;
    }

    private static void applySlotDelta(RtsHandlerCache c, String itemId, long count, boolean isRemoval,
            ItemStack prototype, int slotIndex, boolean newSlotEmpty) {
        if (isRemoval) {
            Long current = c.countsByItem.get(itemId);
            if (current == null) return;
            long newCount = current - count;
            if (newCount <= 0L) {
                c.countsByItem.remove(itemId);
                c.prototypeByItem.remove(itemId);
                c.itemToSlots.remove(itemId);
                c.hasItemType.remove(itemId);
            } else {
                c.countsByItem.put(itemId, newCount);
            }
            List<Integer> slots = c.itemToSlots.get(itemId);
            if (slots != null) slots.remove((Integer) slotIndex);
            if (newSlotEmpty) c.emptySlotCount++;
        } else {
            c.countsByItem.merge(itemId, count, Long::sum);
            if (prototype != null && !prototype.isEmpty()) {
                c.prototypeByItem.putIfAbsent(itemId, prototype);
            }
            c.hasItemType.add(itemId);
            List<Integer> slots = c.itemToSlots.computeIfAbsent(itemId, k -> new ArrayList<>());
            if (!slots.contains(slotIndex)) slots.add(slotIndex);
            c.emptySlotCount = Math.max(0, c.emptySlotCount - 1);
        }
    }

    private static void applySlotDelta(RtsHandlerCache c, String itemId, long count, boolean isRemoval, ItemStack prototype) {
        applySlotDelta(c, itemId, count, isRemoval, prototype, -1, false);
    }

    // ======================================================================
    //  Value type
    // ======================================================================

    public record CachedSlot(String itemId, Item item, long count, ItemStack fullStack, int nbtHash) {
        public static final CachedSlot EMPTY = new CachedSlot("", null, 0, ItemStack.EMPTY, 0);

        boolean isEmpty() {
            return this == EMPTY || itemId.isEmpty();
        }

        ItemStack toItemStack() {
            if (isEmpty() || item == null) return ItemStack.EMPTY;
            ItemStack copy = fullStack.copy();
            copy.setCount((int) Math.min(count, Integer.MAX_VALUE));
            return copy;
        }

        ItemStack toPrototype() {
            if (isEmpty() || item == null) return ItemStack.EMPTY;
            ItemStack proto = fullStack.copy();
            proto.setCount(1);
            return proto;
        }
    }
}
