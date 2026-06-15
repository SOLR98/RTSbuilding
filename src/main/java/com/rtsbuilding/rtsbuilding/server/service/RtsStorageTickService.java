package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.compat.ae2.RtsAe2Compat;
import com.rtsbuilding.rtsbuilding.compat.bd.RtsBdCompat;
import com.rtsbuilding.rtsbuilding.server.storage.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.LinkedItemHandlerView;
import com.rtsbuilding.rtsbuilding.server.storage.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.RtsHandlerCache;
import com.rtsbuilding.rtsbuilding.server.storage.SharedHandlerCacheRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.*;

/**
 * Tick-driven cache-refresh service for all active RTS storage sessions.
 *
 * <p>Inspired by AE2's {@code TickManagerService}: each player's storage is
 * refreshed on an <b>adaptive</b> schedule instead of a fixed interval:
 * <ul>
 *   <li>Items keep changing → speed up to every tick (min responsiveness)</li>
 *   <li>Nothing changes for a while → gradually slow down to reduce CPU load</li>
 *   <li>{@link #alert(UUID)} can be called externally to wake up immediately</li>
 * </ul>
 *
 * <p>This avoids trashing the server with per-tick capability lookups for idle
 * players while still providing near-instant updates when storage is active.
 */
public final class RtsStorageTickService {

    public static final RtsStorageTickService INSTANCE = new RtsStorageTickService();

    // ---- adaptive rate constants (see RtsServiceConstants) -------------------

    // ---- state ---------------------------------------------------------------

    /** Per-player aggregate storage instance. */
    private final Map<UUID, RtsAggregateStorage> playerStorage = new HashMap<>();

    /** Per-player handler → cache mappings. */
    private final Map<UUID, List<HandlerCachePair>> playerHandlers = new HashMap<>();

    /** Per-player adaptive tick trackers (replaces old fixed counter). */
    private final Map<UUID, TickTracker> tickTrackers = new HashMap<>();

    /** 全局共享缓存注册表: 同一容器多玩家共享一份 RtsHandlerCache。 */
    private final SharedHandlerCacheRegistry sharedCaches = new SharedHandlerCacheRegistry();

    /** 每个共享缓存的自适应 tick 追踪器 (key → TickTracker)。 */
    private final Map<String, SharedHandlerCacheRegistry.TickTracker> sharedTrackers = new HashMap<>();

    private RtsStorageTickService() {
    }

    // ---- lifecycle -------------------------------------------------------------

    /**
     * Registers or updates a player's aggregate storage using shared caches.
     * Multiple players linking the same container will share one RtsHandlerCache.
     */
    public RtsAggregateStorage registerPlayerWithRefs(ServerPlayer player, List<LinkedHandler> linkedHandlers) {
        UUID uuid = player.getUUID();
        RtsAggregateStorage storage = this.playerStorage.computeIfAbsent(uuid, k -> new RtsAggregateStorage());

        // Build handler list and ref map (unwrap LinkedItemHandlerView)
        List<IItemHandler> handlers = new ArrayList<>();
        Map<IItemHandler, String> refKeys = new HashMap<>();
        for (LinkedHandler lh : linkedHandlers) {
            IItemHandler raw = lh.handler();
            if (raw instanceof LinkedItemHandlerView view) raw = view.getRawHandler();
            handlers.add(raw);
            refKeys.put(raw, SharedHandlerCacheRegistry.buildKey(lh.ref()));
        }

        // Unmount stale handlers
        List<HandlerCachePair> existing = this.playerHandlers.getOrDefault(uuid, List.of());
        Set<IItemHandler> existingSet = new HashSet<>();
        Set<String> existingRefKeys = new HashSet<>();
        for (HandlerCachePair p : existing) {
            existingSet.add(p.handler);
            if (p.refKey != null) existingRefKeys.add(p.refKey);
        }
        Set<IItemHandler> newSet = new HashSet<>(handlers);
        Set<String> newRefKeys = new HashSet<>(refKeys.values());

        // Unmount removed handlers + release refs
        for (HandlerCachePair p : existing) {
            if (!newSet.contains(p.handler)) {
                storage.unmount(p.handler);
                if (p.refKey != null && !newRefKeys.contains(p.refKey)) {
                    sharedCaches.release(p.refKey);
                }
            }
        }

        // Mount new handlers with shared caches
        List<HandlerCachePair> newPairs = new ArrayList<>();
        Map<IItemHandler, RtsHandlerCache> existingCacheMap = new HashMap<>();
        for (HandlerCachePair p : existing) {
            existingCacheMap.put(p.handler, p.cache);
        }

        for (int i = 0; i < handlers.size(); i++) {
            IItemHandler handler = handlers.get(i);
            String refKey = refKeys.get(handler);
            RtsHandlerCache cache;

            if (existingCacheMap.containsKey(handler)) {
                cache = existingCacheMap.get(handler);
            } else if (refKey != null && !isNetworkHandler(handler)) {
                // 共享缓存: 多个玩家链接同一普通容器时复用
                cache = sharedCaches.acquire(refKey, handler);
                sharedCaches.addRef(refKey);
                if (!cache.hasData()) {
                    cache.update(handler);
                }
                storage.mount(handlers.size() - i, handler, cache);
            } else {
                // 私有缓存: AE2/BD 网络处理器或无 refKey 的处理器
                cache = new RtsHandlerCache();
                cache.update(handler);
                storage.mount(handlers.size() - i, handler, cache);
            }
            newPairs.add(new HandlerCachePair(handler, cache, refKey));
        }

        this.playerHandlers.put(uuid, newPairs);
        int initialRate = calculateInitialRate(handlers);
        this.tickTrackers.computeIfAbsent(uuid, k -> new TickTracker(initialRate));
        return storage;
    }

    /**
     * Removes a player's storage cache entirely and releases
     * all cached data for immediate GC.
     */
    public void unregisterPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        this.playerStorage.remove(uuid);

        // Release cache data structures proactively so the GC can
        // reclaim the large slot/count arrays before the cache objects
        // themselves become unreachable.
        List<HandlerCachePair> pairs = this.playerHandlers.remove(uuid);
        if (pairs != null) {
            for (HandlerCachePair p : pairs) {
                if (p.refKey != null) {
                    // 共享缓存的引用计数 -1（共享箱子的缓存不释放）
                    sharedCaches.release(p.refKey);
                } else {
                    // 私有缓存的引用计数 -1 并释放
                    p.cache.release();
                }
                RtsAe2Compat.releaseNetworkHandler(p.handler);
                RtsBdCompat.releaseNetworkHandler(p.handler);
            }
        }

        this.tickTrackers.remove(uuid);
    }

    // ---- tick (adaptive) -------------------------------------------------------

    /**
     * Called on every server tick for all active players.
     * Uses AE2-style adaptive scheduling: speeds up when busy, slows when idle.
     *
     * @return map of player UUID → set of changed item IDs since last refresh
     */
    public Map<UUID, Set<String>> tick() {
        Map<UUID, Set<String>> allChanges = new HashMap<>();

        // ── Shared cache adaptive update (每个容器独立调速) ──
        tickSharedCachesAdaptive();

        for (UUID uuid : this.playerHandlers.keySet()) {
            TickTracker tracker = this.tickTrackers.get(uuid);
            if (tracker == null) continue;

            // Check if it's time for this player's next refresh
            tracker.ticksSinceRefresh++;
            if (tracker.ticksSinceRefresh < tracker.currentRate) {
                continue;
            }
            tracker.ticksSinceRefresh = 0;

            RtsAggregateStorage storage = this.playerStorage.get(uuid);
            if (storage == null) continue;

            Set<String> changes = storage.tickUpdate();

            if (!changes.isEmpty()) {
                // ── Changes detected → speed up like AE2's URGENT/FASTER ──
                tracker.currentRate = Math.max(RtsServiceConstants.MIN_TICK_RATE, tracker.currentRate / 2);
                tracker.consecutiveIdle = 0;
                allChanges.put(uuid, changes);
            } else {
                // ── No changes → gradually slow down like AE2's IDLE ──
                tracker.consecutiveIdle++;
                if (tracker.consecutiveIdle > RtsServiceConstants.IDLE_THRESHOLD) {
                    tracker.currentRate = Math.min(RtsServiceConstants.MAX_TICK_RATE, tracker.currentRate + 1);
                }
            }
        }

        return allChanges;
    }

    /**
     * 对每个共享缓存独立执行自适应更新。
     * 变化频繁 → 加速到每 tick；无变化 → 逐渐降速到最多 60 tick。
     */
    private void tickSharedCachesAdaptive() {
        sharedCaches.tickAll(sharedTrackers,
                RtsServiceConstants.IDLE_THRESHOLD,
                RtsServiceConstants.MAX_TICK_RATE);
    }

    /** AE2 和 BD 网络处理器不共享缓存 — 每玩家独立实例。 */
    private static boolean isNetworkHandler(IItemHandler handler) {
        return handler instanceof com.rtsbuilding.rtsbuilding.compat.AnySlotInsertItemHandler
                || handler instanceof com.rtsbuilding.rtsbuilding.compat.bd.RtsBdCompat.DirectExtractHandler;
    }

    // ---- alert (like AE2's alertDevice) ----------------------------------------

    /**
     * Wakes up a player's storage ticker immediately, forcing the next refresh
     * to happen without delay. Equivalent to AE2's {@code alertDevice()}.
     * <p>
     * Call this after RTS system insert/extract operations so the GUI reflects
     * changes on the very next tick instead of waiting for the adaptive timer.
     */
    public void alert(UUID playerUuid) {
        TickTracker tracker = this.tickTrackers.get(playerUuid);
        if (tracker != null) {
            tracker.currentRate = RtsServiceConstants.MIN_TICK_RATE;
            tracker.ticksSinceRefresh = RtsServiceConstants.MIN_TICK_RATE; // Will trigger on next tick
            tracker.consecutiveIdle = 0;
        }
    }

    /**
     * Forces an immediate cache refresh for a specific player and returns
     * the changes. Also resets the adaptive timer to run again next tick.
     */
    public Set<String> forceRefresh(ServerPlayer player) {
        UUID uuid = player.getUUID();
        RtsAggregateStorage storage = this.playerStorage.get(uuid);
        if (storage == null) return Set.of();

        TickTracker tracker = this.tickTrackers.get(uuid);
        if (tracker != null) {
            tracker.ticksSinceRefresh = tracker.currentRate; // Force immediate on next tick too
        }
        return storage.tickUpdate();
    }

    // ---- accessors -------------------------------------------------------------

    /**
     * Returns the aggregate storage for a player, or {@code null} if not registered.
     */
    public RtsAggregateStorage getStorage(ServerPlayer player) {
        return this.playerStorage.get(player.getUUID());
    }

    /**
     * Calculates the initial refresh rate based on total slot count.
     * <p>
     * Uses a logarithmic formula: {@code rate = ceil(log2(slots / 27 + 1))}.
     * <ul>
     *   <li>1 chest (27 slots) → rate=1 (every tick)</li>
     *   <li>5 chests (135 slots) → rate=3</li>
     *   <li>10 chests (270 slots) → rate=4</li>
     *   <li>100 chests (2700 slots) → rate=7</li>
     * </ul>
     * This ensures smooth scaling: few slots = instant response,
     * many slots = graceful back-off without abrupt threshold jumps.
     */
    private static int calculateInitialRate(List<IItemHandler> handlers) {
        if (handlers == null || handlers.isEmpty()) return RtsServiceConstants.DEFAULT_TICK_RATE;
        int totalSlots = 0;
        for (IItemHandler h : handlers) {
            try {
                totalSlots += h.getSlots();
            } catch (Exception ignored) {
            }
        }
        if (totalSlots <= 0) return RtsServiceConstants.MIN_TICK_RATE;
        // Logarithmic scaling: rate = ceil(log2(slots / 27 + 1))
        // 27 is one chest's slot count, used as the base unit.
        double logValue = Math.log((double) totalSlots / 27.0 + 1.0) / Math.log(2.0);
        int rate = (int) Math.ceil(logValue);
        return Math.max(RtsServiceConstants.MIN_TICK_RATE, Math.min(RtsServiceConstants.MAX_INITIAL_RATE, rate));
    }

    // ---- value types -----------------------------------------------------------

    record HandlerCachePair(IItemHandler handler, RtsHandlerCache cache, String refKey) {
    }

    /**
     * Per-player adaptive tick state, analogous to AE2's {@code TickTracker}.
     */
    private static final class TickTracker {
        /** Current adaptive rate (ticks between refreshes). */
        int currentRate;
        /** Ticks elapsed since the last refresh. */
        int ticksSinceRefresh = 0;
        /** Consecutive refresh cycles with zero changes. */
        int consecutiveIdle = 0;

        TickTracker(int initialRate) {
            this.currentRate = initialRate;
        }
    }
}
