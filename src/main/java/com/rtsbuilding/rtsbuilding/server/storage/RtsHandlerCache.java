package com.rtsbuilding.rtsbuilding.server.storage;

import com.rtsbuilding.rtsbuilding.compat.RefreshableSnapshotHandler;
import com.rtsbuilding.rtsbuilding.compat.ReportedCountItemHandler;
import com.rtsbuilding.rtsbuilding.compat.AnySlotInsertItemHandler;
import com.rtsbuilding.rtsbuilding.compat.ae2.RtsAe2Compat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;

/**
 * Slot-level cache for a single {@link IItemHandler} with change detection.
 *
 * <p>Uses a slot-snapshot pattern: each call to {@link #update(IItemHandler)}
 * diffs against the previous snapshot to return only the set of items that
 * changed. This avoids repeatedly calling {@code getStackInSlot()} on every
 * page refresh or transfer operation.
 *
 * <p>The cache provides both aggregated counts (for the storage browser) and
 * representative ItemStack prototypes (for exact NBT-component matching).
 *
 * <p>Design inspired by AE2's {@code ExternalInventoryCache}.
 */
public final class RtsHandlerCache {

    /** Cached slot snapshots: index → CachedSlot with full ItemStack. */
    private CachedSlot[] front = new CachedSlot[0];

    /** Accumulated counts keyed by canonical item ID. */
    private final Map<String, Long> countsByItem = new HashMap<>();

    /** Representative (count=1) stacks keyed by item ID, for exact-entry building. */
    private final Map<String, ItemStack> prototypeByItem = new HashMap<>();

    /** itemId → 该容器内持有该物品的槽位号列表。索引与 countsByItem 同步维护。 */
    private final Map<String, List<Integer>> itemToSlots = new HashMap<>();

    /** 该容器当前的空槽位数。 */
    private int emptySlotCount;

    /** 该容器内存在的物品类型集合。用于插入路由快速判断。 */
    private final Set<String> hasItemType = new HashSet<>();

    /** Whether the cache has been dirtied since last clear. */
    private volatile boolean dirtySinceLastRead;

    /** AE2 KeyCounter 上一帧快照 (itemId → count)。仅 AE2 处理器使用。 */
    private Map<String, Long> ae2LastItemCounts;

    // ======================================================================
    //  Cache update
    // ======================================================================

    /**
     * Scans all slots in the handler, diffs against the previous snapshot,
     * and returns the set of item IDs that changed.
     *
     * <p>Aggregate counts ({@link #countsByItem}) and prototype stacks
     * ({@link #prototypeByItem}) are updated <b>incrementally</b> — only
     * slots that actually changed affect the maps. This avoids a full
     * O(n) rebuild on every tick in large AE2-style storage systems.
     *
     * <p>For AE2 / AnySlotInsertItemHandler identifiers, the KeyCounter
     * diret diff path is used instead of the per-slot scanning path.
     */
    public Set<String> update(IItemHandler handler) {
        Objects.requireNonNull(handler, "handler");

        // ── AE2 fast path: KeyCounter diff ──
        if (handler instanceof ReportedCountItemHandler && handler instanceof AnySlotInsertItemHandler) {
            return updateFromAe2Diff(handler);
        }

        // Refresh snapshot-based handlers once per cycle (non-AE2 path)
        if (handler instanceof RefreshableSnapshotHandler refreshable) {
            refreshable.ensureFreshSnapshot();
        }

        // ── Slotted container path ──
        int slots = numSlots(handler);

        // Grow buffer if needed
        if (slots > this.front.length) {
            this.front = Arrays.copyOf(this.front, slots);
        }

        Set<String> changes = new HashSet<>();

        // For ReportedCountItemHandler (e.g. AE2), slot stacks are prototypes
        // that don't vary NBT per-slot, so we can skip the expensive
        // isSameItemSameComponents() check in hasChanged().
        boolean skipNbtCompare = handler instanceof ReportedCountItemHandler;

        // ── Phase 1: Scan changed slots and apply incremental deltas ──
        for (int slot = 0; slot < slots; slot++) {
            CachedSlot oldEntry = this.front[slot];
            CachedSlot newEntry = readSlot(handler, slot);
            this.front[slot] = newEntry;

            if (!hasChanged(oldEntry, newEntry, skipNbtCompare)) {
                continue;
            }

            // Remove old slot's contribution
            if (oldEntry != null && !oldEntry.isEmpty()) {
                changes.add(oldEntry.itemId());
                boolean newIsEmpty = newEntry == null || newEntry.isEmpty();
                applySlotDelta(oldEntry.itemId(), oldEntry.count, true, null, slot, newIsEmpty);
            }

            // Add new slot's contribution
            if (newEntry != null && !newEntry.isEmpty()) {
                changes.add(newEntry.itemId());
                ItemStack prototype = skipNbtCompare
                        ? newEntry.fullStack
                        : newEntry.toPrototype();
                // new item being added: slot was either empty before or held a different item
                // → the slot is being filled, so it's not a "new empty" situation
                applySlotDelta(newEntry.itemId(), newEntry.count, false, prototype, slot, false);
            }
        }

        // Count empty slots for this container (first scan or full recount)
        if (this.emptySlotCount <= 0 && this.countsByItem.isEmpty()) {
            int count = 0;
            for (int s = 0; s < slots; s++) {
                CachedSlot entry = this.front[s];
                if (entry == null || entry.isEmpty()) count++;
            }
            this.emptySlotCount = count;
        }

        // ── Phase 2: Handle slot count shrinking ──
        if (slots < this.front.length) {
            for (int slot = slots; slot < this.front.length; slot++) {
                CachedSlot oldEntry = this.front[slot];
                if (oldEntry != null && !oldEntry.isEmpty()) {
                    changes.add(oldEntry.itemId());
                    applySlotDelta(oldEntry.itemId(), oldEntry.count, true, null);
                }
                this.front[slot] = null;
            }
            this.front = Arrays.copyOf(this.front, slots);
        }

        if (!changes.isEmpty()) {
            this.dirtySinceLastRead = true;
        }
        return changes;
    }

    // ── AE2 KeyCounter diff path ──

    /**
     * 对 AE2 网络处理器的 KeyCounter 快照做差分，无需遍历槽位。
     * O(不同物品类型数)，每条比较只涉及两个 long 值。
     */
    private Set<String> updateFromAe2Diff(IItemHandler handler) {
        Set<String> changes = new HashSet<>();

        if (this.ae2LastItemCounts == null || this.ae2LastItemCounts.isEmpty()) {
            // 首次: 单次遍历 KeyCounter 收集全量计数和原型
            Map<String, Long> current = new HashMap<>();
            RtsAe2Compat.collectCountsAndPrototypes(handler, current, this.prototypeByItem);
            for (var entry : current.entrySet()) {
                this.countsByItem.put(entry.getKey(), entry.getValue());
                changes.add(entry.getKey());
            }
            this.ae2LastItemCounts = current;
        } else {
            Map<String, Long> current = RtsAe2Compat.getCurrentItemCounts(handler);
            // 差分对比: 变更 / 删除
            for (var entry : this.ae2LastItemCounts.entrySet()) {
                String id = entry.getKey();
                long oldCount = entry.getValue();
                long newCount = current.getOrDefault(id, 0L);
                if (newCount != oldCount) {
                    changes.add(id);
                    if (newCount > 0L) {
                        this.countsByItem.put(id, newCount);
                    } else {
                        this.countsByItem.remove(id);
                    }
                }
            }
            // 新增
            for (var entry : current.entrySet()) {
                String id = entry.getKey();
                if (!this.ae2LastItemCounts.containsKey(id)) {
                    long count = entry.getValue();
                    changes.add(id);
                    this.countsByItem.put(id, count);
                    if (!this.prototypeByItem.containsKey(id)) {
                        ItemStack proto = RtsAe2Compat.getPrototypeFromHandler(handler, id);
                        if (!proto.isEmpty()) {
                            this.prototypeByItem.put(id, proto);
                        }
                    }
                }
            }
            this.ae2LastItemCounts = current;
        }

        if (!changes.isEmpty()) {
            this.dirtySinceLastRead = true;
        }
        return changes;
    }

    // ======================================================================
    //  Query API
    // ======================================================================

    /** Returns total count of the given item across all cached slots. */
    public long getCount(Item item) {
        return this.countsByItem.getOrDefault(item.toString(), 0L);
    }

    /** Returns total count by item registry string ID. */
    public long getCount(String itemId) {
        return this.countsByItem.getOrDefault(itemId, 0L);
    }

    /**
     * Dumps all cached counts into the provided map, accumulating into
     * existing values.
     */
    public void getAvailableItems(Map<String, Long> out) {
        for (var entry : this.countsByItem.entrySet()) {
            out.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
    }

    /**
     * Returns a representative (count=1) ItemStack with full NBT for the
     * given item ID, or {@link ItemStack#EMPTY} if not cached.
     */
    public ItemStack getPrototype(String itemId) {
        ItemStack stack = this.prototypeByItem.get(itemId);
        return stack != null ? stack.copy() : ItemStack.EMPTY;
    }

    /**
     * Returns the full slot snapshot, or {@link CachedSlot#EMPTY}.
     */
    public CachedSlot getSlot(int slot) {
        if (slot < 0 || slot >= this.front.length) {
            return CachedSlot.EMPTY;
        }
        CachedSlot entry = this.front[slot];
        return entry != null ? entry : CachedSlot.EMPTY;
    }

    /** Returns the ItemStack as stored in the cached slot. */
    public ItemStack getStackInSlot(int slot) {
        CachedSlot entry = getSlot(slot);
        return entry.isEmpty() ? ItemStack.EMPTY : entry.toItemStack();
    }

    /** Returns how many slots are currently cached. */
    public int getCachedSlotCount() {
        return this.front.length;
    }

    /** 缓存是否已有数据（非首次使用）。 */
    public boolean hasData() {
        return !this.countsByItem.isEmpty() || this.front.length > 0;
    }

    /** 返回持有该物品的所有槽位号列表。无则返回空列表。 */
    public List<Integer> getSlotsFor(String itemId) {
        List<Integer> slots = this.itemToSlots.get(itemId);
        return slots != null ? slots : List.of();
    }

    /** 该容器是否有指定物品类型。 */
    public boolean hasItemType(String itemId) {
        return this.hasItemType.contains(itemId);
    }

    /** 该容器内所有物品类型集合（只读快照）。 */
    public Set<String> getHasItemTypes() {
        return this.hasItemType.isEmpty() ? Set.of() : new HashSet<>(this.hasItemType);
    }

    /** 该容器当前空槽位数。 */
    public int getEmptySlotCount() {
        return this.emptySlotCount;
    }

    /** Returns true if the cache has been dirtied since last {@link #clearDirty()}. */
    public boolean isDirty() {
        return this.dirtySinceLastRead;
    }

    /** Clears the dirty flag. */
    public void clearDirty() {
        this.dirtySinceLastRead = false;
    }

    /** Invalidates the entire cache, forcing a full rebuild on next update. */
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

    /**
     * Releases all internal data so the GC can reclaim memory immediately.
     * <p>
     * Unlike {@link #invalidate()}, this method nulls out the map references
     * so the entries can be collected even if the cache object itself is held
     * briefly. <b>Do not call {@link #update(IItemHandler)} after this</b>
     * without first calling {@link #invalidate()}.
     */
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
    //  Internals
    // ======================================================================

    private int numSlots(IItemHandler handler) {
        try {
            return handler.getSlots();
        } catch (Exception e) {
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
            // Use real reported count for AE2/BDSlot etc. that return representative stacks
            long count = (handler instanceof ReportedCountItemHandler rc)
                    ? Math.max(0L, rc.getReportedCount(slot))
                    : stack.getCount();
            // ReportedCount handlers (e.g. AE2 network) return a fresh copy of the
            // prototype via getStackInSlot() — safe to retain directly. Vanilla handlers
            // return a live reference to the slot's ItemStack, which may be mutated
            // externally — must snapshot it to keep a consistent cache.
            ItemStack stored = (handler instanceof ReportedCountItemHandler)
                    ? stack
                    : stack.copy();
            int nbtHash = stored.hashCode();
            return new CachedSlot(id.toString(), stack.getItem(), count, stored, nbtHash);
        } catch (Exception e) {
            return CachedSlot.EMPTY;
        }
    }

    private static boolean hasChanged(CachedSlot oldEntry, CachedSlot newEntry, boolean skipNbtCompare) {
        if (oldEntry == null && newEntry == null) return false;
        if (oldEntry == null || newEntry == null) return true;
        if (!oldEntry.itemId.equals(newEntry.itemId)) return true;
        if (oldEntry.count != newEntry.count) return true;
        // For ReportedCountItemHandler (e.g. AE2 network), the display stack
        // is a prototype and NBT doesn't vary per-slot — skip the expensive
        // isSameItemSameComponents() check to avoid 10000+ NBT comparisons.
        if (!skipNbtCompare && oldEntry.count > 0 && newEntry.count > 0) {
            // Same object reference → definitely identical (non-copying handlers)
            if (oldEntry.fullStack == newEntry.fullStack) return false;
            // NBT hash quick-reject: different hash → component set definitely changed
            if (oldEntry.nbtHash != 0 && newEntry.nbtHash != 0 && oldEntry.nbtHash != newEntry.nbtHash) return true;
            // Deep compare only for same-hash or zero-hash (fallback)
            if (!ItemStack.isSameItemSameComponents(oldEntry.fullStack, newEntry.fullStack)) return true;
        }
        return false;
    }

    /**
     * 应用槽位增量变化，同时更新物品→槽位索引和容器信息。
     */
    private void applySlotDelta(String itemId, long count, boolean isRemoval,
            ItemStack prototype, int slotIndex, boolean newSlotEmpty) {
        if (isRemoval) {
            Long current = this.countsByItem.get(itemId);
            if (current == null) return;
            long newCount = current - count;
            if (newCount <= 0L) {
                this.countsByItem.remove(itemId);
                this.prototypeByItem.remove(itemId);
                this.itemToSlots.remove(itemId);
                this.hasItemType.remove(itemId);
            } else {
                this.countsByItem.put(itemId, newCount);
            }
            // 从物品索引中移除该槽位
            List<Integer> slots = this.itemToSlots.get(itemId);
            if (slots != null) slots.remove((Integer) slotIndex);

            // 如果该槽位变成空的 → 空槽位+1
            if (newSlotEmpty) this.emptySlotCount++;
        } else {
            this.countsByItem.merge(itemId, count, Long::sum);
            if (prototype != null && !prototype.isEmpty()) {
                this.prototypeByItem.putIfAbsent(itemId, prototype);
            }
            this.hasItemType.add(itemId);
            // 往物品索引中添加该槽位
            List<Integer> slots = this.itemToSlots.computeIfAbsent(itemId, k -> new ArrayList<>());
            if (!slots.contains(slotIndex)) slots.add(slotIndex);

            // 该槽位被填入物品 → 空槽位-1
            this.emptySlotCount = Math.max(0, this.emptySlotCount - 1);
        }
    }

    /** 旧签名，无槽位索引维护（用于 Phase 2 缩容清理）。 */
    private void applySlotDelta(String itemId, long count, boolean isRemoval, ItemStack prototype) {
        applySlotDelta(itemId, count, isRemoval, prototype, -1, false);
    }

    // ======================================================================
    //  Value type
    // ======================================================================

    /**
     * A cached slot snapshot. Stores both the logical count and the full
     * ItemStack for NBT-preserving comparisons.
     */
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
