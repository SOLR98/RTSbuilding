package com.rtsbuilding.rtsbuilding.server.util;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能断点监控工具，测量关键路径的操作耗时与调用频率。
 *
 * <p>使用方式：
 * <pre>{@code
 *   ProfilerToken t = RtsPerformanceMonitor.start("操作名", stack);
 *   // ... 被监控代码 ...
 *   t.end();            // 标准结束
 *   t.end(remain);      // 带剩余物品记录
 * }</pre>
 *
 * <p>统计结果通过日志输出，支持按操作名汇总：总耗时、调用次数、平均耗时。
 */
public final class RtsPerformanceMonitor {

    private static final boolean ENABLED = false;
    private static final long WARN_THRESHOLD_NS = 5_000_000L; // 5ms 警告

    // 按操作名汇总
    private static final ConcurrentMap<String, AggregateStats> aggregates = new ConcurrentHashMap<>();

    private RtsPerformanceMonitor() {
    }

    /**
     * 开始测量一个操作。
     *
     * @param operation 操作名称（如 "storeToLinkedOnly", "extractMatching"）
     * @param context   关联的物品上下文，可为空
     */
    public static ProfilerToken start(String operation, Object context) {
        if (!ENABLED) return ProfilerToken.NOOP;
        return new ProfilerToken(operation, System.nanoTime(), context);
    }

    /**
     * 打印所有操作的汇总统计到日志。
     */
    public static void printSummary() {
        if (!ENABLED || aggregates.isEmpty()) return;
        RtsbuildingMod.LOGGER.info("═════════ RTS 性能断点汇总 ═════════");
        RtsbuildingMod.LOGGER.info(String.format("%-40s %10s %12s %12s %12s",
                "操作", "调用次数", "总耗时(ms)", "平均耗时(μs)", "最慢(μs)"));
        RtsbuildingMod.LOGGER.info("─────────────────────────────────────────────────────────────────────");
        for (var entry : aggregates.entrySet()) {
            AggregateStats s = entry.getValue();
            long avgUs = s.count.get() > 0 ? s.totalNs.get() / s.count.get() / 1000 : 0;
            long maxUs = s.maxNs.get() / 1000;
            RtsbuildingMod.LOGGER.info(String.format("%-40s %10d %12.2f %12d %12d",
                    entry.getKey(), s.count.get(), s.totalNs.get() / 1_000_000.0, avgUs, maxUs));
        }
        RtsbuildingMod.LOGGER.info("═══════════════════════════════════════════════════════════════════════");
    }

    public static void reset() {
        aggregates.clear();
    }

    // ======================================================================

    public static class ProfilerToken {
        static final ProfilerToken NOOP = new ProfilerToken("", 0, null) {
            @Override public void end() { }
            @Override public void end(ItemStack remain) { }
            @Override public void end(boolean success) { }
        };

        private final String operation;
        private final long startNanos;
        private final Object context;

        ProfilerToken(String operation, long startNanos, Object context) {
            this.operation = operation;
            this.startNanos = startNanos;
            this.context = context;
        }

        /** 结束测量，记录耗时。 */
        public void end() {
            recordElapsed();
        }

        /** 结束测量，记录剩余物品信息。 */
        public void end(ItemStack remain) {
            long elapsed = recordElapsed();
            if (elapsed > WARN_THRESHOLD_NS && remain != null && !remain.isEmpty()) {
                RtsbuildingMod.LOGGER.warn("[PERF] {} 耗时 {}μs，剩余 {}x {}",
                        operation, elapsed / 1000, remain.getCount(), remain.getHoverName().getString());
            }
        }

        /** 结束测量，记录操作结果。 */
        public void end(boolean success) {
            long elapsed = recordElapsed();
            if (elapsed > WARN_THRESHOLD_NS) {
                RtsbuildingMod.LOGGER.warn("[PERF] {} {} 耗时 {}μs",
                        operation, success ? "成功" : "失败", elapsed / 1000);
            }
        }

        private long recordElapsed() {
            if (this == NOOP) return 0;
            long elapsed = System.nanoTime() - startNanos;
            aggregates.computeIfAbsent(operation, k -> new AggregateStats())
                    .record(elapsed);
            return elapsed;
        }
    }

    // ======================================================================

    private static final class AggregateStats {
        final AtomicLong totalNs = new AtomicLong();
        final AtomicLong maxNs = new AtomicLong();
        final AtomicLong count = new AtomicLong();

        void record(long elapsed) {
            totalNs.addAndGet(elapsed);
            count.incrementAndGet();
            maxNs.updateAndGet(m -> Math.max(m, elapsed));
        }
    }
}
