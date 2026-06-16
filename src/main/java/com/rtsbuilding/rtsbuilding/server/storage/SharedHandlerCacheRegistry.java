package com.rtsbuilding.rtsbuilding.server.storage;

import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局共享的 Handler 缓存注册表。
 *
 * <p>多个玩家链接同一容器时复用一份 {@link RtsHandlerCache}，避免重复扫描。
 * 引用计数归零时自动释放缓存。每个缓存关联其 IItemHandler，独立更新。
 *
 * <p>仅供 {@link com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService}
 * 内部使用，不对外暴露。
 */
final class SharedHandlerCacheRegistry {

    private final ConcurrentHashMap<String, RtsHandlerCache> caches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IItemHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> refCounts = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定链接位置的共享缓存，关联其 IItemHandler。
     */
    RtsHandlerCache acquire(String key, IItemHandler handler) {
        handlers.putIfAbsent(key, handler);
        return caches.computeIfAbsent(key, k -> new RtsHandlerCache());
    }

    /** 增加引用计数。 */
    void addRef(String key) {
        refCounts.merge(key, 1, Integer::sum);
    }

    /**
     * 减少引用计数。归零时释放缓存和 handler 引用。
     */
    boolean release(String key) {
        int count = refCounts.merge(key, -1, (old, delta) -> Math.max(0, old + delta));
        if (count <= 0) {
            refCounts.remove(key);
            handlers.remove(key);
            RtsHandlerCache cache = caches.remove(key);
            if (cache != null) {
                cache.release();
                return true;
            }
        }
        return false;
    }

    /**
     * 按自适应频率更新共享缓存。仅更新已到期 (ticksSinceRefresh >= rate) 的缓存。
     *
     * @param trackers key → TickTracker，在调用方维护
     * @param idleThreshold 连续无变化多少次后开始降速
     * @param maxTickRate 最大间隔 (tick)
     */
    Map<String, java.util.Set<String>> tickAll(Map<String, TickTracker> trackers,
            int idleThreshold, int maxTickRate) {
        Map<String, java.util.Set<String>> allChanges = new HashMap<>();
        for (var entry : caches.entrySet()) {
            String key = entry.getKey();
            IItemHandler handler = handlers.get(key);
            if (handler == null) continue;

            TickTracker tracker = trackers.computeIfAbsent(key, k -> new TickTracker());
            tracker.ticksSinceRefresh++;
            if (tracker.ticksSinceRefresh < tracker.currentRate) continue;
            tracker.ticksSinceRefresh = 0;

            RtsHandlerCache cache = entry.getValue();
            java.util.Set<String> changes = cache.update(handler);
            allChanges.put(key, changes);

            if (!changes.isEmpty()) {
                tracker.currentRate = Math.max(1, tracker.currentRate / 2);
                tracker.consecutiveIdle = 0;
            } else {
                tracker.consecutiveIdle++;
                if (tracker.consecutiveIdle > idleThreshold) {
                    tracker.currentRate = Math.min(maxTickRate, tracker.currentRate + 1);
                }
            }
        }
        return allChanges;
    }

    void clear() {
        for (RtsHandlerCache cache : caches.values()) {
            cache.release();
        }
        caches.clear();
        handlers.clear();
        refCounts.clear();
    }

    static String buildKey(LinkedStorageRef ref) {
        return ref.dimension().location() + "@" + ref.pos().toShortString();
    }

    /** 每个共享缓存的自适应 tick 状态。 */
    static final class TickTracker {
        int currentRate = 8;
        int ticksSinceRefresh;
        int consecutiveIdle;
    }
}
