package com.rtsbuilding.rtsbuilding.server.storage;

import com.rtsbuilding.rtsbuilding.compat.AnySlotInsertItemHandler;
import com.rtsbuilding.rtsbuilding.compat.SlotlessItemHandler;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RtsAggregateStorage {

    /** Priority → list of cached handler views. Sorted descending (highest first). */
    private final NavigableMap<Integer, List<CachedHandlerSlot>> priorityMounts = new TreeMap<>(
            (a, b) -> Integer.compare(b, a));

    /** Flat list rebuilt after each mount/unmount change. */
    private volatile List<CachedHandlerSlot> flatOrdered = List.of();

    /** Changes accumulated across all handlers since last poll. */
    private final Set<String> pendingChanges = ConcurrentHashMap.newKeySet();

    /** Reentrancy guard for insert/extract. */
    private final AtomicBoolean inUse = new AtomicBoolean(false);

    /** itemId → 最优插入目标缓存。 */
    private volatile Map<String, CachedHandlerSlot> insertionCache = new ConcurrentHashMap<>();

    /**
     * Pending mount/unmount operations queued during inUse=true.
     */
    private final Queue<Runnable> pendingMutations = new ConcurrentLinkedQueue<>();

    // ── 路由计划 (插入端) ──

    /** 插入多目标路由计划。itemId → 有空间+有该物品的handler列表(高优先级在前)。 */
    private volatile InsertRoutingPlan routingPlan = InsertRoutingPlan.EMPTY;

    /** 有插入操作改变了容器空间状态，路由需局部更新或重建。 */
    private volatile boolean routingPlanDirty;

    /** 本tick内因插入操作导致状态变化的handler集合，用于实时局部更新路由。 */
    private final Set<CachedHandlerSlot> dirtyInsertHandlers = ConcurrentHashMap.newKeySet();

    // ── 路由计划 (提取端) ──

    /** 提取多目标路由计划。itemId → 持有该物品的handler列表(低优先级在前)。 */
    private volatile ExtractionRoutingPlan extractionPlan = ExtractionRoutingPlan.EMPTY;

    /** 有提取操作改变了容器状态，路由需局部更新或重建。 */
    private volatile boolean extractionPlanDirty;

    /** 本tick内因提取操作导致状态变化的handler集合。 */
    private final Set<CachedHandlerSlot> dirtyExtractionHandlers = ConcurrentHashMap.newKeySet();

    // ── 分级重建 & 重入处理 ──

    /** tick计数器，用于插入/提取路由的分级交替重建。 */
    private int tickCounter;

    /** 重入排队: inUse=true时收到的插入请求暂存于此，在finally块中处理。 */
    private final Queue<ItemStack> pendingInsertBuffer = new ConcurrentLinkedQueue<>();

    // ---- mount / unmount -------------------------------------------------------

    /**
     * Mounts a handler with the given priority and associates a cache with it.
     */
    public void mount(int priority, IItemHandler handler, RtsHandlerCache cache) {
        if (inUse.get()) {
            this.pendingMutations.add(() -> {
                doMount(priority, handler, cache);
            });
            return;
        }
        doMount(priority, handler, cache);
    }

    private void doMount(int priority, IItemHandler handler, RtsHandlerCache cache) {
        this.priorityMounts
                .computeIfAbsent(priority, k -> new ArrayList<>())
                .add(new CachedHandlerSlot(priority, handler, cache));
        rebuildFlatOrder();
    }

    /**
     * Unmounts a handler by identity.
     */
    public void unmount(IItemHandler handler) {
        if (inUse.get()) {
            this.pendingMutations.add(() -> doUnmount(handler));
            return;
        }
        doUnmount(handler);
    }

    private void doUnmount(IItemHandler handler) {
        for (var entry : this.priorityMounts.entrySet()) {
            entry.getValue().removeIf(cs -> cs.handler == handler);
        }
        this.priorityMounts.entrySet().removeIf(e -> e.getValue().isEmpty());
        rebuildFlatOrder();
    }

    // ======================================================================
    //  路由计划 — 插入端
    // ======================================================================

    /**
     * 插入多目标路由计划。
     * itemToTargets: itemId → 有空间+有该物品的handler(高优先级在前)
     * globalFallback: 所有有空间的handler(高优先级在前)
     */
    record InsertRoutingPlan(
            Map<String, List<CachedHandlerSlot>> itemToTargets,
            List<CachedHandlerSlot> globalFallback) {

        static final InsertRoutingPlan EMPTY = new InsertRoutingPlan(Map.of(), List.of());

        List<CachedHandlerSlot> getTargets(String itemId) {
            return itemToTargets.getOrDefault(itemId, globalFallback);
        }
    }

    /**
     * 全量构建插入路由计划。从缓存数据重建，只包含有空位的handler。
     * 网络型存储（AnySlotInsertItemHandler，如AE2/BD）无slot概念，始终视为有空间。
     */
    private void rebuildInsertRoutingPlan() {
        Map<String, List<CachedHandlerSlot>> itemToTargets = new HashMap<>();
        List<CachedHandlerSlot> allWithSpace = new ArrayList<>();

        for (CachedHandlerSlot cs : this.flatOrdered) {
            boolean hasSpace = cs.cache.getEmptySlotCount() > 0
                    || cs.handler instanceof AnySlotInsertItemHandler;
            if (!hasSpace)
                continue;
            allWithSpace.add(cs);
            for (String itemId : cs.cache.getHasItemTypes()) {
                itemToTargets.computeIfAbsent(itemId, k -> new ArrayList<>()).add(cs);
            }
        }

        this.routingPlan = new InsertRoutingPlan(
                Collections.unmodifiableMap(itemToTargets),
                Collections.unmodifiableList(allWithSpace));
        this.routingPlanDirty = false;
        this.dirtyInsertHandlers.clear();
    }

    /**
     * 插入路由局部更新。移除已满的handler。
     * 网络型存储（AnySlotInsertItemHandler）始终保留——无slot概念，永不"满"。
     */
    private void updateInsertRoutingPlanLocally() {
        if (dirtyInsertHandlers.isEmpty())
            return;
        Set<CachedHandlerSlot> dirty = Set.copyOf(dirtyInsertHandlers);
        dirtyInsertHandlers.clear();

        List<CachedHandlerSlot> newFallback = this.routingPlan.globalFallback.stream()
                .filter(cs -> !dirty.contains(cs) || cs.handler instanceof AnySlotInsertItemHandler)
                .toList();

        Map<String, List<CachedHandlerSlot>> newTargets = new HashMap<>();
        for (var entry : this.routingPlan.itemToTargets.entrySet()) {
            List<CachedHandlerSlot> filtered = entry.getValue().stream()
                    .filter(cs -> !dirty.contains(cs) || cs.handler instanceof AnySlotInsertItemHandler)
                    .toList();
            if (!filtered.isEmpty())
                newTargets.put(entry.getKey(), filtered);
        }

        this.routingPlan = new InsertRoutingPlan(
                Collections.unmodifiableMap(newTargets),
                Collections.unmodifiableList(newFallback));
        this.routingPlanDirty = dirtyInsertHandlers.isEmpty();
    }

    /** 智能ensure：脏标志触发时优先局部更新，避免全量重建。 */
    private void ensureInsertRoutingPlan() {
        if (routingPlan == InsertRoutingPlan.EMPTY) {
            rebuildInsertRoutingPlan();
            return;
        }
        if (!routingPlanDirty)
            return;
        updateInsertRoutingPlanLocally();
    }

    // ======================================================================
    //  路由计划 — 提取端
    // ======================================================================

    /**
     * 提取多目标路由计划。
     * itemToSources: itemId → 持有该物品的handler(低优先级在前=优先被取走)
     * allWithItems: 所有持有任意物品的handler(低优先级在前)
     */
    record ExtractionRoutingPlan(
            Map<String, List<CachedHandlerSlot>> itemToSources,
            List<CachedHandlerSlot> allWithItems) {

        static final ExtractionRoutingPlan EMPTY = new ExtractionRoutingPlan(Map.of(), List.of());

        List<CachedHandlerSlot> getSources(String itemId) {
            return itemToSources.getOrDefault(itemId, allWithItems);
        }
    }

    /**
     * 全量构建提取路由计划。从缓存数据重建，低优先级在前。
     */
    private void rebuildExtractionPlan() {
        Map<String, List<CachedHandlerSlot>> itemToSources = new HashMap<>();
        List<CachedHandlerSlot> allWithItems = new ArrayList<>();

        // 低优先级在前 → 从flatOrdered末尾开始
        for (int i = this.flatOrdered.size() - 1; i >= 0; i--) {
            CachedHandlerSlot cs = this.flatOrdered.get(i);
            if (cs.cache.getHasItemTypes().isEmpty())
                continue;
            allWithItems.add(cs);
            for (String itemId : cs.cache.getHasItemTypes()) {
                if (cs.cache.getCount(itemId) > 0) {
                    itemToSources.computeIfAbsent(itemId, k -> new ArrayList<>()).add(cs);
                }
            }
        }

        this.extractionPlan = new ExtractionRoutingPlan(
                Collections.unmodifiableMap(itemToSources),
                Collections.unmodifiableList(allWithItems));
        this.extractionPlanDirty = false;
        this.dirtyExtractionHandlers.clear();
    }

    /**
     * 提取路由局部更新。移除已空的handler。
     * 网络型存储（AnySlotInsertItemHandler）始终保留——提取1个物品不代表handler变空。
     */
    private void updateExtractionPlanLocally() {
        if (dirtyExtractionHandlers.isEmpty())
            return;
        Set<CachedHandlerSlot> dirty = Set.copyOf(dirtyExtractionHandlers);
        dirtyExtractionHandlers.clear();

        Map<String, List<CachedHandlerSlot>> newSources = new HashMap<>();
        for (var entry : this.extractionPlan.itemToSources.entrySet()) {
            List<CachedHandlerSlot> filtered = entry.getValue().stream()
                    .filter(cs -> !dirty.contains(cs) || cs.handler instanceof AnySlotInsertItemHandler)
                    .toList();
            if (!filtered.isEmpty())
                newSources.put(entry.getKey(), filtered);
        }

        List<CachedHandlerSlot> newAll = this.extractionPlan.allWithItems.stream()
                .filter(cs -> !dirty.contains(cs) || cs.handler instanceof AnySlotInsertItemHandler)
                .toList();

        this.extractionPlan = new ExtractionRoutingPlan(
                Collections.unmodifiableMap(newSources),
                Collections.unmodifiableList(newAll));
        this.extractionPlanDirty = dirtyExtractionHandlers.isEmpty();
    }

    /** 智能ensure：脏标志触发时优先局部更新，避免全量重建。 */
    private void ensureExtractionPlan() {
        if (extractionPlan == ExtractionRoutingPlan.EMPTY) {
            rebuildExtractionPlan();
            return;
        }
        if (!extractionPlanDirty)
            return;
        updateExtractionPlanLocally();
    }

    // ======================================================================
    //  insert (原有+路由增强)
    // ======================================================================

    /**
     * Attempts to insert an item stack into the aggregate storage.
     * 重入保护：并发调用时物品进入pendingInsertBuffer排队，不会丢失。
     *
     * @return the remainder (items that could not be stored)
     */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty() || this.flatOrdered.isEmpty()) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        if (!inUse.compareAndSet(false, true)) {
            if (!simulate)
                this.pendingInsertBuffer.add(stack.copy());
            return ItemStack.EMPTY;
        }
        try {
            return doInsert(stack, simulate);
        } finally {
            inUse.set(false);
            applyPendingMutations();
            drainInsertBuffer();
        }
    }

    private ItemStack doInsert(ItemStack stack, boolean simulate) {
        ItemStack remain = stack.copy();

        // ── 缓存路径: O(1) 查找最优插入目标 ──
        String itemId = stack.getItem().toString();
        CachedHandlerSlot cached = this.insertionCache.get(itemId);
        if (cached != null) {
            remain = insertToHandler(cached.handler, remain, simulate);
            if (remain.isEmpty())
                return remain;
        }

        // ── Fallback: 全扫描 ──
        List<CachedHandlerSlot> remaining = new ArrayList<>();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            if (remain.isEmpty())
                break;
            if (cs == cached)
                continue;
            if (cs.cache.getCount(stack.getItem()) > 0) {
                remain = insertToHandler(cs.handler, remain, simulate);
                trackChange(stack.getItem(), remain, stack, simulate);
                if (!simulate && remain.getCount() < stack.getCount()) {
                    cs.cache.invalidate();
                    dirtyInsertHandlers.add(cs);
                    routingPlanDirty = true;
                }
            } else {
                remaining.add(cs);
            }
        }
        for (CachedHandlerSlot cs : remaining) {
            if (remain.isEmpty())
                break;
            int before = remain.getCount();
            remain = insertToHandler(cs.handler, remain, simulate);
            trackChange(stack.getItem(), remain, stack, simulate);
            if (!simulate && remain.getCount() < before) {
                cs.cache.invalidate();
                dirtyInsertHandlers.add(cs);
                routingPlanDirty = true;
            }
        }

        return remain;
    }

    /** 从各容器的 hasItemType 和 emptySlotCount 重建插入目标缓存。网络型存储始终视为有空间。 */
    private void rebuildInsertionCache() {
        Map<String, CachedHandlerSlot> next = new ConcurrentHashMap<>();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            int emptySlots = cs.cache.getEmptySlotCount();
            boolean hasSpace = emptySlots > 0 || cs.handler instanceof AnySlotInsertItemHandler;
            if (!hasSpace)
                continue;
            for (String itemId : cs.cache.getHasItemTypes()) {
                next.putIfAbsent(itemId, cs);
            }
        }
        this.insertionCache = next;
    }

    // ======================================================================
    //  路由执行 — 插入端
    // ======================================================================

    /**
     * 基于预计算插入路由计划执行单物品插入。
     * 只遍历可能有空间的handler而非全部handler。
     */
    public ItemStack executeInsertRoute(ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty() || this.flatOrdered.isEmpty()) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        if (!inUse.compareAndSet(false, true)) {
            if (!simulate)
                this.pendingInsertBuffer.add(stack.copy());
            return ItemStack.EMPTY;
        }
        try {
            ensureInsertRoutingPlan();
            String itemId = stack.getItem().toString();
            List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);

            ItemStack remain = stack.copy();
            for (CachedHandlerSlot cs : targets) {
                int before = remain.getCount();
                remain = insertToHandler(cs.handler, remain, simulate);
                int inserted = before - remain.getCount();
                if (inserted > 0 && !simulate) {
                    cs.cache.invalidate();
                    dirtyInsertHandlers.add(cs);
                    routingPlanDirty = true;
                    trackChange(stack.getItem(), remain, stack, simulate);
                }
                if (remain.isEmpty())
                    break;
            }
            return remain;
        } finally {
            inUse.set(false);
            applyPendingMutations();
            drainInsertBuffer();
        }
    }

    /**
     * 基于预计算路由计划批量插入多种物品。
     * 一次路由查表覆盖所有物品类型。
     *
     * @return 未能完全存入的 overflow (itemId → remaining stack)
     */
    public Map<String, ItemStack> batchInsert(Map<String, ItemStack> items, boolean simulate) {
        if (items == null || items.isEmpty() || this.flatOrdered.isEmpty())
            return items != null ? items : Map.of();
        if (!inUse.compareAndSet(false, true)) {
            if (!simulate) {
                for (var entry : items.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty())
                        this.pendingInsertBuffer.add(entry.getValue().copy());
                }
            }
            return Map.of();
        }
        try {
            ensureInsertRoutingPlan();
            Map<String, ItemStack> overflow = new HashMap<>();

            for (var entry : items.entrySet()) {
                String itemId = entry.getKey();
                ItemStack stack = entry.getValue();
                if (stack == null || stack.isEmpty())
                    continue;

                List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);
                ItemStack remain = stack.copy();
                for (CachedHandlerSlot cs : targets) {
                    int before = remain.getCount();
                    remain = insertToHandler(cs.handler, remain, simulate);
                    if (before != remain.getCount() && !simulate) {
                        cs.cache.invalidate();
                        dirtyInsertHandlers.add(cs);
                        routingPlanDirty = true;
                        trackChange(stack.getItem(), remain, stack, simulate);
                    }
                    if (remain.isEmpty())
                        break;
                }
                if (!remain.isEmpty())
                    overflow.put(itemId, remain);
            }
            return overflow;
        } finally {
            inUse.set(false);
            applyPendingMutations();
            drainInsertBuffer();
        }
    }

    /** 处理重入排队的待插入物品。 */
    private void drainInsertBuffer() {
        ItemStack item;
        while ((item = this.pendingInsertBuffer.poll()) != null) {
            if (item.isEmpty())
                continue;
            String itemId = item.getItem().toString();
            ensureInsertRoutingPlan();
            List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);
            ItemStack remain = item;
            for (CachedHandlerSlot cs : targets) {
                remain = insertToHandler(cs.handler, remain, false);
                if (remain.isEmpty())
                    break;
            }
            if (!remain.isEmpty())
                this.pendingInsertBuffer.add(remain);
            break; // 防止无限循环
        }
    }

    // ======================================================================
    //  路由执行 — 提取端
    // ======================================================================

    /**
     * Extracts items matching the given predicate from the aggregate storage.
     * Lower-priority handlers are drained first.
     */
    public ItemStack extract(Item targetItem, int limit) {
        return executeExtractRoute(targetItem, null, limit);
    }

    /**
     * Extracts items matching both the item type and NBT components.
     */
    public ItemStack extractMatching(Item targetItem, ItemStack preferred, int limit) {
        return executeExtractRoute(targetItem, preferred, limit);
    }

    /**
     * 基于预计算提取路由计划执行物品提取。
     * 只遍历真正持有该物品的handler。
     */
    public ItemStack executeExtractRoute(Item targetItem, ItemStack preferred, int limit) {
        if (targetItem == null || limit <= 0 || this.flatOrdered.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!inUse.compareAndSet(false, true))
            return ItemStack.EMPTY;
        try {
            ensureExtractionPlan();
            String itemId = targetItem.toString();
            List<CachedHandlerSlot> sources = extractionPlan.getSources(itemId);

            int remaining = limit;
            ItemStack out = ItemStack.EMPTY;

            for (CachedHandlerSlot cs : sources) {
                if (remaining <= 0)
                    break;

                ItemStack part = extractOneHandler(cs, targetItem, preferred, remaining);
                if (part.isEmpty())
                    continue;

                if (out.isEmpty()) {
                    out = part;
                } else if (ItemStack.isSameItemSameComponents(out, part)) {
                    out.grow(part.getCount());
                }
                remaining -= part.getCount();

                cs.cache.invalidate();
                dirtyExtractionHandlers.add(cs);
                extractionPlanDirty = true;
                this.pendingChanges.add(itemId);
            }

            return out;
        } finally {
            inUse.set(false);
            applyPendingMutations();
        }
    }

    /**
     * 从路由计划中提取与原型ItemStack精确NBT匹配的物品。
     * 用于建造和蓝图场景。
     */
    public ItemStack extractMatchingPrototype(ItemStack prototype, int limit) {
        if (prototype == null || prototype.isEmpty())
            return ItemStack.EMPTY;
        return executeExtractRoute(prototype.getItem(), prototype, limit);
    }

    // ---- available stacks ------------------------------------------------------

    /**
     * Populates the given map with the total counts from all cached handlers.
     */
    public void getAvailableItems(Map<String, Long> out) {
        for (CachedHandlerSlot cs : this.flatOrdered) {
            cs.cache.getAvailableItems(out);
        }
    }

    /**
     * Populates both count map and prototype map from all cached handlers.
     */
    public void collectAllItems(Map<String, Long> counts, Map<String, ItemStack> protos) {
        for (CachedHandlerSlot cs : this.flatOrdered) {
            cs.cache.getAvailableItems(counts);
            for (var entry : counts.entrySet()) {
                if (!protos.containsKey(entry.getKey())) {
                    ItemStack proto = cs.cache.getPrototype(entry.getKey());
                    if (proto != null && !proto.isEmpty()) {
                        protos.put(entry.getKey(), proto);
                    }
                }
            }
        }
    }

    // ---- tick update -----------------------------------------------------------

    /**
     * Updates all handler caches by scanning changed slots.
     * 分级重建插入/提取路由计划，控制大规模场景下的CPU开销。
     */
    public Set<String> tickUpdate() {
        Set<String> changes = new HashSet<>();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            changes.addAll(cs.cache.update(cs.handler));
        }

        if (!this.pendingChanges.isEmpty()) {
            changes.addAll(drainPendingChanges());
        }

        applyPendingMutations();

        // insertionCache: 始终重建 (开销小，插入关键路径)
        rebuildInsertionCache();

        // 路由计划: 分级重建
        int handlerCount = this.flatOrdered.size();
        if (handlerCount < 100) {
            rebuildInsertRoutingPlan();
            rebuildExtractionPlan();
        } else if (handlerCount < 500) {
            if (tickCounter % 2 == 0)
                rebuildInsertRoutingPlan();
            else
                rebuildExtractionPlan();
        } else {
            if (tickCounter % 3 == 0)
                rebuildInsertRoutingPlan();
            else if (tickCounter % 3 == 1)
                rebuildExtractionPlan();
        }
        tickCounter++;

        return changes;
    }

    /**
     * Returns and clears the pending changes accumulated from insert/extract
     * operations since the last call.
     */
    public Set<String> drainPendingChanges() {
        Set<String> drained = new HashSet<>(this.pendingChanges);
        this.pendingChanges.clear();
        return drained;
    }

    /**
     * Returns whether any cached handler reports having the given item.
     */
    public boolean hasItem(Item item) {
        String itemId = item.toString();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            if (cs.cache.getCount(itemId) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the total count of the given item across all cached handlers.
     */
    public long getTotalCount(Item item) {
        long total = 0L;
        String itemId = item.toString();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            total += cs.cache.getCount(itemId);
        }
        return total;
    }

    /**
     * Returns a representative (count=1) ItemStack for the given item ID,
     * or {@link ItemStack#EMPTY} if not cached.
     */
    public ItemStack getPrototype(String itemId) {
        for (CachedHandlerSlot cs : this.flatOrdered) {
            ItemStack proto = cs.cache.getPrototype(itemId);
            if (!proto.isEmpty()) {
                return proto;
            }
        }
        return ItemStack.EMPTY;
    }

    public boolean isEmpty() {
        return this.flatOrdered.isEmpty();
    }

    // ---- internals -------------------------------------------------------------

    private void rebuildFlatOrder() {
        List<CachedHandlerSlot> list = new ArrayList<>();
        for (var entry : this.priorityMounts.entrySet()) {
            list.addAll(entry.getValue());
        }
        this.flatOrdered = Collections.unmodifiableList(list);
    }

    private static ItemStack insertToHandler(IItemHandler handler, ItemStack stack, boolean simulate) {
        if (handler == null || stack == null || stack.isEmpty()) {
            return stack == null ? ItemStack.EMPTY : stack;
        }

        if (handler instanceof AnySlotInsertItemHandler anySlot) {
            return anySlot.insertItemAnywhere(stack, simulate);
        }

        if (handler instanceof SlotlessItemHandler slotless) {
            return slotless.insertItemSlotless(stack, simulate);
        }

        ItemStack remain = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remain.isEmpty(); slot++) {
            remain = handler.insertItem(slot, remain, simulate);
        }
        return remain;
    }

    private static ItemStack extractOneHandler(CachedHandlerSlot cs, Item targetItem, ItemStack preferred,
            int limit) {
        IItemHandler handler = cs.handler;
        if (handler == null || targetItem == null || limit <= 0) {
            return ItemStack.EMPTY;
        }

        if ((preferred == null || preferred.isEmpty()) && handler instanceof AnySlotInsertItemHandler anySlot) {
            return anySlot.extractItemAnywhere(targetItem, limit, false);
        }

        if ((preferred == null || preferred.isEmpty())
                && handler instanceof SlotlessItemHandler slotless) {
            ItemStack toExtract = new ItemStack(targetItem, limit);
            ItemStack result = slotless.extractItemSlotless(toExtract, false);
            if (!result.isEmpty())
                return result;
        }

        int remaining = limit;
        ItemStack out = ItemStack.EMPTY;

        String targetId = targetItem.toString();
        List<Integer> indexSlots = cs.cache.getSlotsFor(targetId);
        if (!indexSlots.isEmpty()) {
            for (int slot : indexSlots) {
                if (remaining <= 0)
                    break;
                ItemStack extracted = handler.extractItem(slot, remaining, false);
                if (extracted.isEmpty())
                    continue;
                if (preferred != null && !preferred.isEmpty()
                        && !ItemStack.isSameItemSameComponents(extracted, preferred)) {
                    handler.insertItem(slot, extracted, false);
                    continue;
                }
                if (out.isEmpty()) {
                    out = extracted;
                } else if (ItemStack.isSameItemSameComponents(out, extracted)) {
                    out.grow(extracted.getCount());
                }
                remaining -= extracted.getCount();
            }
            if (remaining <= 0 || !out.isEmpty())
                return out;
        }

        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            ItemStack slotStack = handler.getStackInSlot(slot);
            if (slotStack.isEmpty() || slotStack.getItem() != targetItem) {
                continue;
            }
            if (preferred != null && !preferred.isEmpty()
                    && !ItemStack.isSameItemSameComponents(slotStack, preferred)) {
                continue;
            }
            ItemStack extracted = handler.extractItem(slot, remaining, false);
            if (extracted.isEmpty())
                continue;

            if (out.isEmpty()) {
                out = extracted;
            } else if (ItemStack.isSameItemSameComponents(out, extracted)) {
                out.grow(extracted.getCount());
            } else {
                ItemStack leftover = handler.insertItem(slot, extracted, false);
                if (leftover.isEmpty()) {
                    continue;
                }
                if (out.isEmpty()) {
                    out = leftover;
                } else {
                    out.grow(leftover.getCount());
                }
                remaining = 0;
                break;
            }
            remaining -= extracted.getCount();
        }
        return out;
    }

    private void trackChange(Item originalItem, ItemStack remain, ItemStack original, boolean simulate) {
        if (!simulate && remain.getCount() < original.getCount()) {
            this.pendingChanges.add(originalItem.toString());
        }
    }

    private void applyPendingMutations() {
        Runnable mutation;
        while ((mutation = this.pendingMutations.poll()) != null) {
            mutation.run();
        }
    }

    // ---- value type ------------------------------------------------------------

    record CachedHandlerSlot(int priority, IItemHandler handler, RtsHandlerCache cache) {
    }
}
