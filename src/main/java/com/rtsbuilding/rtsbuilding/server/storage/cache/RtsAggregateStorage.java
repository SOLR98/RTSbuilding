package com.rtsbuilding.rtsbuilding.server.storage.cache;

import com.rtsbuilding.rtsbuilding.compat.AnySlotInsertItemHandler;
import com.rtsbuilding.rtsbuilding.compat.SlotlessItemHandler;
import com.rtsbuilding.rtsbuilding.server.service.RtsServiceConstants;
import com.rtsbuilding.rtsbuilding.server.util.RtsPerformanceMonitor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RtsAggregateStorage {

    /** Priority → list of cached handler views. Sorted descending (highest first). */
    private final NavigableMap<Integer, List<CachedHandlerSlot>> priorityMounts = new TreeMap<>(
            (a, b) -> Integer.compare(b, a));

    /** Lock guarding all access to priorityMounts. */
    private final Object mountLock = new Object();

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

    /** 本tick内因插入操作导致状态变化的handler集合。 */
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

    /** 重入排队: inUse=true时收到的插入请求暂存于此。 */
    private final Queue<ItemStack> pendingInsertBuffer = new ConcurrentLinkedQueue<>();

    // ---- mount / unmount -------------------------------------------------------

    public void mount(int priority, IItemHandler handler, RtsHandlerCache cache) {
        if (!inUse.compareAndSet(false, true)) {
            this.pendingMutations.add(() -> {
                doMount(priority, handler, cache);
            });
            return;
        }
        try {
            doMount(priority, handler, cache);
        } finally {
            inUse.set(false);
        }
    }

    private void doMount(int priority, IItemHandler handler, RtsHandlerCache cache) {
        synchronized (mountLock) {
            this.priorityMounts
                    .computeIfAbsent(priority, k -> new ArrayList<>())
                    .add(new CachedHandlerSlot(priority, handler, cache));
            rebuildFlatOrder();
        }
        this.routingPlanDirty = true;
        this.extractionPlanDirty = true;
    }

    public void unmount(IItemHandler handler) {
        if (!inUse.compareAndSet(false, true)) {
            this.pendingMutations.add(() -> doUnmount(handler));
            return;
        }
        try {
            doUnmount(handler);
        } finally {
            inUse.set(false);
        }
    }

    private void doUnmount(IItemHandler handler) {
        synchronized (mountLock) {
            for (var entry : this.priorityMounts.entrySet()) {
                entry.getValue().removeIf(cs -> cs.handler == handler);
            }
            this.priorityMounts.entrySet().removeIf(e -> e.getValue().isEmpty());
            rebuildFlatOrder();
        }
        this.routingPlanDirty = true;
        this.extractionPlanDirty = true;
    }

    // ======================================================================
    //  路由计划 — 插入端
    // ======================================================================

    record InsertRoutingPlan(
            Map<String, List<CachedHandlerSlot>> itemToTargets,
            List<CachedHandlerSlot> globalFallback) {

        static final InsertRoutingPlan EMPTY = new InsertRoutingPlan(Map.of(), List.of());

        List<CachedHandlerSlot> getTargets(String itemId) {
            List<CachedHandlerSlot> preferred = itemToTargets.get(itemId);
            if (preferred == null || preferred.isEmpty())
                return globalFallback;
            List<CachedHandlerSlot> all = new ArrayList<>(preferred);
            for (CachedHandlerSlot cs : globalFallback) {
                if (!preferred.contains(cs))
                    all.add(cs);
            }
            return all;
        }
    }

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

    private void updateInsertRoutingPlanLocally() {
        if (dirtyInsertHandlers.isEmpty())
            return;
        Set<CachedHandlerSlot> dirty = Set.copyOf(dirtyInsertHandlers);
        dirtyInsertHandlers.clear();

        List<CachedHandlerSlot> newFallback = this.routingPlan.globalFallback.stream()
                .filter(cs -> !dirty.contains(cs)
                        || cs.handler instanceof AnySlotInsertItemHandler
                        || cs.cache.getEmptySlotCount() > 0)
                .toList();

        Map<String, List<CachedHandlerSlot>> newTargets = new HashMap<>();
        for (var entry : this.routingPlan.itemToTargets.entrySet()) {
            List<CachedHandlerSlot> filtered = entry.getValue().stream()
                    .filter(cs -> !dirty.contains(cs)
                            || cs.handler instanceof AnySlotInsertItemHandler
                            || cs.cache.getEmptySlotCount() > 0)
                    .toList();
            if (!filtered.isEmpty())
                newTargets.put(entry.getKey(), filtered);
        }

        this.routingPlan = new InsertRoutingPlan(
                Collections.unmodifiableMap(newTargets),
                Collections.unmodifiableList(newFallback));
        this.routingPlanDirty = dirtyInsertHandlers.isEmpty();
    }

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

    record ExtractionRoutingPlan(
            Map<String, List<CachedHandlerSlot>> itemToSources,
            List<CachedHandlerSlot> allWithItems) {

        static final ExtractionRoutingPlan EMPTY = new ExtractionRoutingPlan(Map.of(), List.of());

        List<CachedHandlerSlot> getSources(String itemId) {
            return itemToSources.getOrDefault(itemId, allWithItems);
        }
    }

    private void rebuildExtractionPlan() {
        Map<String, List<CachedHandlerSlot>> itemToSources = new HashMap<>();
        List<CachedHandlerSlot> allWithItems = new ArrayList<>();

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
    //  insert
    // ======================================================================

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
            int before = remain.getCount();
            remain = insertToHandler(cached.handler, remain, simulate);
            if (!simulate && remain.getCount() < before) {
                trackChange(stack.getItem(), remain, stack, simulate);
                dirtyInsertHandlers.add(cached);
                routingPlanDirty = true;
            }
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
                dirtyInsertHandlers.add(cs);
                routingPlanDirty = true;
            }
        }

        return remain;
    }

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
            var _t = RtsPerformanceMonitor.start("RtsAggregateStorage.executeInsertRoute", stack);
            ensureInsertRoutingPlan();
            String itemId = stack.getItem().toString();
            List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);

            ItemStack remain = stack.copy();
            int budget = RtsServiceConstants.BATCH_INSERT_BUDGET;
            for (CachedHandlerSlot cs : targets) {
                boolean isNetwork = cs.handler instanceof AnySlotInsertItemHandler;
                int before = remain.getCount();
                remain = insertToHandler(cs.handler, remain, simulate);
                if (!isNetwork && !simulate) budget--;
                if (before - remain.getCount() > 0 && !simulate) {
                    dirtyInsertHandlers.add(cs);
                    routingPlanDirty = true;
                    trackChange(stack.getItem(), remain, stack, simulate);
                }
                if (!remain.isEmpty() && !isNetwork && budget <= 0) {
                    break;
                }
                if (remain.isEmpty()) break;
            }
            _t.end(remain);
            return remain;
        } finally {
            inUse.set(false);
            applyPendingMutations();
            drainInsertBuffer();
        }
    }

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
            var _t = RtsPerformanceMonitor.start("RtsAggregateStorage.batchInsert", items);
            ensureInsertRoutingPlan();
            Map<String, ItemStack> overflow = new HashMap<>();
            int budget = RtsServiceConstants.BATCH_INSERT_BUDGET;

            List<Map.Entry<String, ItemStack>> entryList = new ArrayList<>(items.entrySet());

            for (int i = 0; i < entryList.size(); i++) {
                var entry = entryList.get(i);
                String itemId = entry.getKey();
                ItemStack stack = entry.getValue();
                if (stack == null || stack.isEmpty())
                    continue;

                List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);
                ItemStack remain = stack.copy();
                for (CachedHandlerSlot cs : targets) {
                    boolean isNetwork = cs.handler instanceof AnySlotInsertItemHandler;
                    int before = remain.getCount();
                    remain = insertToHandler(cs.handler, remain, simulate);
                    if (!isNetwork && !simulate) {
                        budget--;
                    }
                    if (before != remain.getCount() && !simulate) {
                        dirtyInsertHandlers.add(cs);
                        routingPlanDirty = true;
                        trackChange(stack.getItem(), remain, stack, simulate);
                    }
                    if (remain.isEmpty())
                        break;
                    if (!isNetwork && budget <= 0) {
                        if (!remain.isEmpty())
                            overflow.put(itemId, remain);
                        for (int j = i + 1; j < entryList.size(); j++) {
                            var tail = entryList.get(j);
                            if (tail.getValue() != null && !tail.getValue().isEmpty())
                                overflow.put(tail.getKey(), tail.getValue().copy());
                        }
                        _t.end();
                        return overflow;
                    }
                }
                if (!remain.isEmpty())
                    overflow.put(itemId, remain);
            }
            _t.end();
            return overflow;
        } finally {
            inUse.set(false);
            applyPendingMutations();
            drainInsertBuffer();
        }
    }

    private void drainInsertBuffer() {
        ItemStack item;
        int budget = RtsServiceConstants.BATCH_INSERT_BUDGET;
        while ((item = this.pendingInsertBuffer.poll()) != null) {
            if (item.isEmpty())
                continue;
            if (budget <= 0) {
                this.pendingInsertBuffer.add(item);
                break;
            }
            String itemId = item.getItem().toString();
            ensureInsertRoutingPlan();
            List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);
            ItemStack remain = item;
            for (CachedHandlerSlot cs : targets) {
                remain = insertToHandler(cs.handler, remain, false);
                if (remain.isEmpty())
                    break;
                budget--;
            }
            if (!remain.isEmpty())
                this.pendingInsertBuffer.add(remain);
        }
    }

    public void drainPendingForDisconnect(ServerPlayer player) {
        ItemStack item;
        while ((item = this.pendingInsertBuffer.poll()) != null) {
            if (item.isEmpty())
                continue;
            if (!inUse.compareAndSet(false, true)) {
                this.pendingInsertBuffer.add(item);
                return;
            }
            try {
                ensureInsertRoutingPlan();
                String itemId = item.getItem().toString();
                List<CachedHandlerSlot> targets = routingPlan.getTargets(itemId);
                for (CachedHandlerSlot cs : targets) {
                    item = insertToHandler(cs.handler, item, false);
                    if (item.isEmpty()) break;
                }
                if (!item.isEmpty()) {
                    player.drop(item, false);
                }
            } finally {
                inUse.set(false);
            }
        }
    }

    // ======================================================================
    //  路由执行 — 提取端
    // ======================================================================

    public ItemStack extract(Item targetItem, int limit) {
        return executeExtractRoute(targetItem, null, limit);
    }

    public ItemStack extractMatching(Item targetItem, ItemStack preferred, int limit) {
        return executeExtractRoute(targetItem, preferred, limit);
    }

    public ItemStack executeExtractRoute(Item targetItem, ItemStack preferred, int limit) {
        if (targetItem == null || limit <= 0 || this.flatOrdered.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!inUse.compareAndSet(false, true))
            return ItemStack.EMPTY;
        try {
            var _t = RtsPerformanceMonitor.start("RtsAggregateStorage.executeExtractRoute", targetItem);
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

            _t.end(out);
            return out;
        } finally {
            inUse.set(false);
            applyPendingMutations();
        }
    }

    public ItemStack extractMatchingPrototype(ItemStack prototype, int limit) {
        if (prototype == null || prototype.isEmpty())
            return ItemStack.EMPTY;
        return executeExtractRoute(prototype.getItem(), prototype, limit);
    }

    public ItemStack extractMatchingIngredient(Ingredient ingredient, ItemStack preferred, int limit) {
        if (ingredient == null || ingredient.isEmpty() || limit <= 0 || flatOrdered.isEmpty())
            return ItemStack.EMPTY;
        if (!inUse.compareAndSet(false, true))
            return ItemStack.EMPTY;
        try {
            ensureExtractionPlan();
            int remaining = limit;
            ItemStack out = ItemStack.EMPTY;

            for (var entry : extractionPlan.itemToSources.entrySet()) {
                if (remaining <= 0)
                    break;
                ResourceLocation rl = ResourceLocation.tryParse(entry.getKey());
                if (rl == null) continue;
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == null) continue;
                if (!ingredient.test(new ItemStack(item))) continue;

                for (CachedHandlerSlot cs : entry.getValue()) {
                    if (remaining <= 0) break;
                    ItemStack part = extractIngredientFromHandler(cs, ingredient, preferred, remaining);
                    if (part.isEmpty()) continue;
                    if (out.isEmpty()) out = part;
                    else out.grow(part.getCount());
                    remaining -= part.getCount();

                    cs.cache.invalidate();
                    dirtyExtractionHandlers.add(cs);
                    extractionPlanDirty = true;
                    pendingChanges.add(entry.getKey());
                }
            }
            return out;
        } finally {
            inUse.set(false);
            applyPendingMutations();
        }
    }

    private static ItemStack extractIngredientFromHandler(
            CachedHandlerSlot cs, Ingredient ingredient, ItemStack preferred, int limit) {
        IItemHandler handler = cs.handler;
        if (handler == null) return ItemStack.EMPTY;

        List<Integer> priority = null;
        for (ItemStack match : ingredient.getItems()) {
            String itemId = match.getItem().toString();
            List<Integer> idx = cs.cache.getSlotsFor(itemId);
            if (!idx.isEmpty()) {
                if (priority == null) priority = new ArrayList<>();
                priority.addAll(idx);
            }
        }
        boolean hasPriority = priority != null && !priority.isEmpty();

        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0 && hasPriority) {
                for (int slot : priority) {
                    ItemStack extracted = tryExtractSlot(handler, slot, ingredient, preferred, limit);
                    if (!extracted.isEmpty()) return extracted;
                }
            } else if (pass == 1) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    if (hasPriority && priority.contains(slot)) continue;
                    ItemStack extracted = tryExtractSlot(handler, slot, ingredient, preferred, limit);
                    if (!extracted.isEmpty()) return extracted;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack tryExtractSlot(
            IItemHandler handler, int slot, Ingredient ingredient, ItemStack preferred, int limit) {
        ItemStack stack = handler.getStackInSlot(slot);
        if (stack.isEmpty() || !ingredient.test(stack))
            return ItemStack.EMPTY;
        if (preferred != null && !preferred.isEmpty()
                && !ItemStack.isSameItemSameComponents(stack, preferred))
            return ItemStack.EMPTY;
        ItemStack extracted = handler.extractItem(slot, Math.min(limit, stack.getCount()), false);
        if (extracted.isEmpty())
            return ItemStack.EMPTY;
        if (ingredient.test(extracted))
            return extracted;
        ItemStack left = handler.insertItem(slot, extracted, false);
        return left.isEmpty() ? ItemStack.EMPTY : left;
    }

    // ---- available stacks ------------------------------------------------------

    public void getAvailableItems(Map<String, Long> out) {
        for (CachedHandlerSlot cs : this.flatOrdered) {
            cs.cache.getAvailableItems(out);
        }
    }

    public void collectAllItems(Map<String, Long> counts, Map<String, ItemStack> protos) {
        for (CachedHandlerSlot cs : this.flatOrdered) {
            cs.cache.getAvailableItems(counts);
            for (String itemId : cs.cache.getHasItemTypes()) {
                if (!protos.containsKey(itemId)) {
                    ItemStack proto = cs.cache.getPrototype(itemId);
                    if (proto != null && !proto.isEmpty()) {
                        protos.put(itemId, proto);
                    }
                }
            }
        }
    }

    // ---- tick update -----------------------------------------------------------

    public Set<String> tickUpdate() {
        Set<String> changes = new HashSet<>();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            changes.addAll(cs.cache.update(cs.handler));
        }

        if (!this.pendingChanges.isEmpty()) {
            changes.addAll(drainPendingChanges());
        }

        boolean hadPending = !this.pendingMutations.isEmpty();
        applyPendingMutations();

        boolean somethingChanged = !changes.isEmpty() || hadPending;

        if (somethingChanged) {
            rebuildInsertionCache();
        }

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

    public Set<String> drainPendingChanges() {
        Set<String> drained = new HashSet<>(this.pendingChanges);
        this.pendingChanges.clear();
        return drained;
    }

    public boolean hasItem(Item item) {
        String itemId = item.toString();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            if (cs.cache.getCount(itemId) > 0) {
                return true;
            }
        }
        return false;
    }

    public long getTotalCount(Item item) {
        long total = 0L;
        String itemId = item.toString();
        for (CachedHandlerSlot cs : this.flatOrdered) {
            total += cs.cache.getCount(itemId);
        }
        return total;
    }

    public long getTotalCountById(String itemId) {
        long total = 0L;
        for (CachedHandlerSlot cs : this.flatOrdered) {
            total += cs.cache.getCount(itemId);
        }
        return total;
    }

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
        synchronized (mountLock) {
            List<CachedHandlerSlot> list = new ArrayList<>();
            for (var entry : this.priorityMounts.entrySet()) {
                list.addAll(entry.getValue());
            }
            this.flatOrdered = Collections.unmodifiableList(list);
        }
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

        if (preferred != null && !preferred.isEmpty() && handler instanceof AnySlotInsertItemHandler anyPref) {
            ItemStack probe = anyPref.extractItemAnywhere(targetItem, 1, false);
            if (probe.isEmpty())
                return ItemStack.EMPTY;
            if (ItemStack.isSameItemSameComponents(probe, preferred)) {
                int take = Math.min(limit, probe.getCount());
                ItemStack result = probe.copy();
                result.setCount(take);
                if (limit > take) {
                    ItemStack more = anyPref.extractItemAnywhere(targetItem, limit - take, false);
                    if (!more.isEmpty() && ItemStack.isSameItemSameComponents(more, result)) {
                        result.grow(more.getCount());
                    }
                }
                return result;
            }
            anyPref.insertItemAnywhere(probe, false);
            return ItemStack.EMPTY;
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
