package com.rtsbuilding.rtsbuilding.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rtsbuilding.rtsbuilding.server.service.RtsSessionService;
import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.storage.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * RTS 存储压测命令。
 *
 * <p>10 档指数递增压力测试。使用 aggregate 路由计划执行插入/提取，
 * 同时对比无路由暴力路径的性能差异。
 *
 * <pre>
 * Level 1:    10 items  × 10       ops = 100
 * Level 2:    20 items  × 100      ops = 2,000
 * Level 3:    50 items  × 500      ops = 25,000
 * Level 4:   100 items  × 1,000    ops = 100,000
 * Level 5:   200 items  × 5,000    ops = 1M
 * Level 6:   400 items  × 10,000   ops = 4M
 * Level 7:   800 items  × 20,000   ops = 16M
 * Level 8:  1000 items  × 50,000   ops = 50M
 * Level 9:  1000 items  × 100,000  ops = 100M
 * Level 10: 1000 items  × 500,000  ops = 500M
 * </pre>
 */
public final class RtsStressCommand {

    private static final int MAX_UNIQUE_ITEMS = 1000;
    private static final int ROUTE_COMPARE_SAMPLE = 200;

    private RtsStressCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("stress")
                .then(literal("insert")
                        .then(argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> runStress(ctx, Mode.INSERT))))
                .then(literal("extract")
                        .then(argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> runStress(ctx, Mode.EXTRACT))))
                .then(literal("full")
                        .then(argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> runStress(ctx, Mode.FULL_CYCLE))))
                .then(literal("route")
                        .then(argument("level", IntegerArgumentType.integer(1, 8))
                                .executes(ctx -> runStress(ctx, Mode.ROUTE_COMPARE))))
                .then(RtsContinuousStressCommand.build());
    }

    private enum Mode { INSERT, EXTRACT, FULL_CYCLE, ROUTE_COMPARE }

    private static int runStress(CommandContext<CommandSourceStack> ctx, Mode mode) throws CommandSyntaxException {
        int level = IntegerArgumentType.getInteger(ctx, "level");
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        RtsStorageSession session = RtsSessionService.getIfPresent(player);
        if (session == null) {
            ctx.getSource().sendFailure(Component.literal("请先启用 RTS 模式"));
            return 0;
        }

        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        if (linked.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("没有关联的存储容器，请先绑定容器"));
            return 0;
        }

        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
        if (aggregate == null || aggregate.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("聚合存储未就绪，请稍后再试"));
            return 0;
        }

        int uniqueItems = Math.min((int) Math.pow(10, Math.min(level, 3)) * 10, MAX_UNIQUE_ITEMS);
        int opsPerItem = (int) Math.pow(10, level);
        long totalOps = (long) uniqueItems * opsPerItem;

        List<Item> registryItems = collectTestItems(uniqueItems);
        if (registryItems.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("无法收集到足够的测试物品"));
            return 0;
        }

        List<Item> itemsInStorage = scanItemsFromAggregate(aggregate);
        List<Item> insertTargets = itemsInStorage.isEmpty() ? registryItems : itemsInStorage;
        List<Item> extractTargets = itemsInStorage;

        if (mode == Mode.EXTRACT && extractTargets.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("容器内无物品，请先 /rts stress insert 3"));
            return 0;
        }

        if (mode == Mode.ROUTE_COMPARE) {
            return runRouteCompare(ctx, aggregate, insertTargets, extractTargets, opsPerItem, level);
        }

        String modeName = switch (mode) {
            case INSERT -> "insert";
            case EXTRACT -> "extract";
            case FULL_CYCLE -> "fullcycle";
            default -> "unknown";
        };
        ctx.getSource().sendSuccess(() -> Component.literal(
                "压测开始 | level=" + level + " | 路由模式 | 容器内 " + itemsInStorage.size() +
                "种物品 | " + insertTargets.size() + "种×" + opsPerItem + "/" + modeName +
                " | 总=" + totalOps), false);

        long startMs = System.currentTimeMillis();
        long success = 0, fail = 0, failInsertFull = 0, failExtractNotFound = 0;
        long totalInserted = 0, totalExtracted = 0, opCount = 0;

        int sampleInterval = level <= 3 ? 1 : (int) Math.pow(10, level - 3);
        StressPerfCollector insertPerf = new StressPerfCollector(10_000);
        StressPerfCollector extractPerf = new StressPerfCollector(10_000);
        long insertPhaseMs = 0, extractPhaseMs = 0;

        if (mode == Mode.INSERT || mode == Mode.FULL_CYCLE) {
            long phaseStart = System.currentTimeMillis();
            Map<String, ItemStack> batchMap = new HashMap<>();

            for (Item item : insertTargets) {
                int maxStack = Math.max(1, item.getDefaultMaxStackSize());
                int remaining = opsPerItem;
                while (remaining > 0) {
                    int batch = ThreadLocalRandom.current().nextInt(1, Math.min(maxStack, remaining) + 1);
                    ItemStack toInsert = new ItemStack(item, batch);
                    long opStart = (opCount % sampleInterval == 0) ? System.nanoTime() : 0;

                    ItemStack remain = aggregate.executeInsertRoute(toInsert, false);
                    if (opCount % sampleInterval == 0)
                        insertPerf.record(System.nanoTime() - opStart);

                    int placed = batch - remain.getCount();
                    if (placed > 0) { success++; totalInserted += placed; }
                    else { fail++; failInsertFull++; }
                    remaining -= batch;
                    opCount++;
                }
            }
            insertPhaseMs = System.currentTimeMillis() - phaseStart;
        }

        if (mode == Mode.EXTRACT || mode == Mode.FULL_CYCLE) {
            long phaseStart = System.currentTimeMillis();
            for (Item item : extractTargets) {
                int maxStack = Math.max(1, item.getDefaultMaxStackSize());
                int remaining = opsPerItem;
                while (remaining > 0) {
                    int amount = ThreadLocalRandom.current().nextInt(1, Math.min(maxStack, remaining) + 1);
                    long opStart = (opCount % sampleInterval == 0) ? System.nanoTime() : 0;

                    ItemStack result = aggregate.executeExtractRoute(item, null, amount);
                    if (opCount % sampleInterval == 0)
                        extractPerf.record(System.nanoTime() - opStart);

                    if (!result.isEmpty()) { success++; totalExtracted += result.getCount(); }
                    else { fail++; failExtractNotFound++; }
                    remaining -= amount;
                    opCount++;
                }
            }
            extractPhaseMs = System.currentTimeMillis() - phaseStart;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        printResult(ctx, modeName, level, success, fail, elapsedMs, opCount,
                insertPhaseMs, extractPhaseMs, insertPerf, extractPerf, sampleInterval,
                failInsertFull, failExtractNotFound, totalInserted, totalExtracted);

        session.transfer.pageDataVersion.incrementAndGet();
        RtsStorageTickService.INSTANCE.forceRefresh(player);
        return (int) success;
    }

    // ── 路由 vs 暴力路径对比 ──

    private static int runRouteCompare(CommandContext<CommandSourceStack> ctx,
            RtsAggregateStorage aggregate, List<Item> insertItems, List<Item> extractItems,
            int opsPerItem, int level) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "路由对比压测 | level=" + level + " | 各执行" + (ROUTE_COMPARE_SAMPLE * opsPerItem) +
                "次插入+提取"), false);

        long routeInsertNs = 0, routeExtractNs = 0;
        long oldInsertNs = 0, oldExtractNs = 0;
        int routeSamples = 0, oldSamples = 0;

        // ── 路由路径 ──
        for (int iter = 0; iter < ROUTE_COMPARE_SAMPLE; iter++) {
            for (Item item : insertItems) {
                int amount = Math.max(1, item.getDefaultMaxStackSize() / 2);
                ItemStack stack = new ItemStack(item, amount);

                long t0 = System.nanoTime();
                aggregate.executeInsertRoute(stack, false);
                routeInsertNs += System.nanoTime() - t0;
                routeSamples++;
            }
            for (Item item : extractItems) {
                int amount = Math.max(1, item.getDefaultMaxStackSize() / 2);
                long t0 = System.nanoTime();
                aggregate.executeExtractRoute(item, null, amount);
                routeExtractNs += System.nanoTime() - t0;
            }
        }

        // ── 暴力路径: 禁路由直扫 ──
        List<LinkedHandler> linked = null;
        var session = ctx.getSource().getPlayer() != null
                ? RtsSessionService.getIfPresent(ctx.getSource().getPlayer())
                : null;
        if (session != null) {
            linked = RtsLinkedStorageResolver.resolveLinkedHandlers(ctx.getSource().getPlayer(), session);
        }
        if (linked != null && !linked.isEmpty()) {
            var insertHandlers = RtsLinkedStorageResolver.itemHandlersForInsert(linked);
            var extractHandlers = RtsLinkedStorageResolver.itemHandlersForExtract(linked);

            for (int iter = 0; iter < ROUTE_COMPARE_SAMPLE; iter++) {
                for (Item item : insertItems) {
                    int amount = Math.max(1, item.getDefaultMaxStackSize() / 2);
                    ItemStack stack = new ItemStack(item, amount);

                    long t0 = System.nanoTime();
                    bruteInsert(insertHandlers, stack);
                    oldInsertNs += System.nanoTime() - t0;
                    oldSamples++;
                }
                for (Item item : extractItems) {
                    int amount = Math.max(1, item.getDefaultMaxStackSize() / 2);
                    long t0 = System.nanoTime();
                    bruteExtract(extractHandlers, item, amount);
                    oldExtractNs += System.nanoTime() - t0;
                }
            }
        }

        double routeInsertAvg = routeSamples > 0 ? routeInsertNs / (double) routeSamples : 0;
        double routeExtractAvg = routeSamples > 0 ? routeExtractNs / (double) routeSamples : 0;
        double oldInsertAvg = oldSamples > 0 ? oldInsertNs / (double) oldSamples : 0;
        double oldExtractAvg = oldSamples > 0 ? oldExtractNs / (double) oldSamples : 0;

        final int finalRS = routeSamples;
        final int finalOS = oldSamples;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "══ 路由对比 (各%d样本) ══\n" +
                "  插入: 路由 %s/op  暴力 %s/op  → 路由快 %.1f%%\n" +
                "  提取: 路由 %s/op  暴力 %s/op  → 路由快 %.1f%%",
                finalRS,
                formatNanos(routeInsertAvg), formatNanos(oldInsertAvg),
                oldInsertAvg > 0 ? (oldInsertAvg - routeInsertAvg) / oldInsertAvg * 100 : 0,
                formatNanos(routeExtractAvg), formatNanos(oldExtractAvg),
                oldExtractAvg > 0 ? (oldExtractAvg - routeExtractAvg) / oldExtractAvg * 100 : 0)), false);
        return 1;
    }

    // ── 暴力路径 (对比用) ──

    private static ItemStack bruteInsert(List<?> handlers, ItemStack stack) {
        if (handlers == null || handlers.isEmpty()) return stack;
        ItemStack remain = stack.copy();
        for (Object obj : handlers) {
            if (!(obj instanceof net.neoforged.neoforge.items.IItemHandler handler)) continue;
            for (int slot = 0; slot < handler.getSlots() && !remain.isEmpty(); slot++) {
                remain = handler.insertItem(slot, remain, false);
            }
            if (remain.isEmpty()) return ItemStack.EMPTY;
        }
        return remain;
    }

    private static ItemStack bruteExtract(List<?> handlers, Item item, int amount) {
        if (handlers == null || handlers.isEmpty()) return ItemStack.EMPTY;
        int needed = amount;
        ItemStack result = ItemStack.EMPTY;
        for (Object obj : handlers) {
            if (!(obj instanceof net.neoforged.neoforge.items.IItemHandler handler)) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack inSlot = handler.getStackInSlot(slot);
                if (inSlot.isEmpty() || inSlot.getItem() != item) continue;
                ItemStack extracted = handler.extractItem(slot, needed, false);
                if (!extracted.isEmpty()) {
                    needed -= extracted.getCount();
                    if (result.isEmpty()) result = extracted.copy();
                    else result.grow(extracted.getCount());
                    if (needed <= 0) return result;
                }
            }
        }
        return result;
    }

    // ── 辅助方法 ──

    private static List<Item> scanItemsFromAggregate(RtsAggregateStorage aggregate) {
        if (aggregate == null || aggregate.isEmpty()) return List.of();
        Map<String, Long> counts = new HashMap<>();
        aggregate.getAvailableItems(counts);
        List<Item> found = new ArrayList<>();
        for (String id : counts.keySet()) {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null) continue;
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != null) found.add(item);
        }
        return found;
    }

    private static List<Item> collectTestItems(int count) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null) continue;
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) continue;
            items.add(item);
            if (items.size() >= count) break;
        }
        Collections.shuffle(items);
        return items.subList(0, Math.min(count, items.size()));
    }

    // ── 输出 ──

    private static void printResult(CommandContext<CommandSourceStack> ctx,
            String modeName, int level, long success, long fail,
            long elapsedMs, long totalOps, long insertPhaseMs, long extractPhaseMs,
            StressPerfCollector insertPerf, StressPerfCollector extractPerf,
            int sampleInterval, long failInsertFull, long failExtractNotFound,
            long totalInserted, long totalExtracted) {
        double elapsedSec = elapsedMs / 1000.0;
        double opsPerSec = totalOps / Math.max(elapsedSec, 0.001);

        final long fSuccess = success, fFail = fail, fTotalOps = totalOps;
        final double fElapsedSec = elapsedSec, fOpsPerSec = opsPerSec;
        final long fInsertPhaseMs = insertPhaseMs, fExtractPhaseMs = extractPhaseMs;
        final StressPerfCollector fInsertPerf = insertPerf, fExtractPerf = extractPerf;
        final int fSampleInterval = sampleInterval;
        final long fFailInsertFull = failInsertFull, fFailExtractNotFound = failExtractNotFound;
        final long fTotalInserted = totalInserted, fTotalExtracted = totalExtracted;

        ctx.getSource().sendSuccess(() -> Component.literal(
                formatPerfReport(modeName, level, fSuccess, fFail, fElapsedSec,
                        fOpsPerSec, fTotalOps, fInsertPhaseMs, fExtractPhaseMs,
                        fInsertPerf, fExtractPerf, fSampleInterval,
                        fFailInsertFull, fFailExtractNotFound,
                        fTotalInserted, fTotalExtracted)), false);
    }

    private static String formatPerfReport(String modeName, int level, long success, long fail,
            double elapsedSec, double opsPerSec, long totalOps,
            long insertPhaseMs, long extractPhaseMs,
            StressPerfCollector insertPerf, StressPerfCollector extractPerf,
            int sampleInterval, long failInsertFull, long failExtractNotFound,
            long totalInserted, long totalExtracted) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("压测完成(路由) | mode=%s | level=%d | %d ops", modeName, level, totalOps));
        sb.append(String.format("\n  成功: %d  失败: %d  (%.1f%%)", success, fail,
                totalOps > 0 ? (success * 100.0 / totalOps) : 0));
        sb.append(String.format("\n  总耗时: %.2fs  吞吐: %.0f ops/s  总入: %d  总出: %d",
                elapsedSec, opsPerSec, totalInserted, totalExtracted));

        if (failInsertFull > 0 || failExtractNotFound > 0) {
            sb.append("\n  [失败]");
            if (failInsertFull > 0) sb.append(" 容器满:").append(failInsertFull);
            if (failExtractNotFound > 0) sb.append(" 无物:").append(failExtractNotFound);
        }
        if (insertPhaseMs > 0) {
            sb.append(String.format("\n  [INSERT] %dms %.1f%%", insertPhaseMs,
                    (insertPhaseMs * 100.0 / Math.max(1, elapsedSec * 1000))));
            sb.append(formatPerfStats(insertPerf));
        }
        if (extractPhaseMs > 0) {
            sb.append(String.format("\n  [EXTRACT] %dms %.1f%%", extractPhaseMs,
                    (extractPhaseMs * 100.0 / Math.max(1, elapsedSec * 1000))));
            sb.append(formatPerfStats(extractPerf));
        }
        return sb.toString();
    }

    private static String formatPerfStats(StressPerfCollector perf) {
        int count = perf.count();
        if (count == 0) return "";
        return String.format("\n    样本: %d | avg: %s | min: %s | max: %s | p50: %s p99: %s",
                count, formatNanos(perf.avg()), formatNanos(perf.min()), formatNanos(perf.max()),
                formatNanos(perf.percentile(50)), formatNanos(perf.percentile(99)));
    }

    static String formatNanos(double nanos) {
        if (nanos >= 1_000_000_000) return String.format("%.2fs", nanos / 1_000_000_000);
        if (nanos >= 1_000_000) return String.format("%.2fms", nanos / 1_000_000);
        if (nanos >= 1_000) return String.format("%.1fµs", nanos / 1_000);
        return String.format("%.0fns", nanos);
    }

    static String formatNanos(long nanos) { return formatNanos((double) nanos); }

    // ── StressPerfCollector ──

    static final class StressPerfCollector {
        private final long[] samples;
        private int index;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;
        private long sum;

        StressPerfCollector(int maxSamples) { this.samples = new long[maxSamples]; }

        void record(long nanos) {
            int slot = index % samples.length;
            samples[slot] = nanos;
            index++;
            if (nanos < min) min = nanos;
            if (nanos > max) max = nanos;
            sum += nanos;
        }
        int count() { return Math.min(index, samples.length); }
        long min() { return index > 0 ? min : 0; }
        long max() { return index > 0 ? max : 0; }
        double avg() { int n = count(); return n > 0 ? (double) sum / n : 0; }
        long percentile(double p) {
            int n = count();
            if (n == 0) return 0;
            long[] sorted = new long[n];
            System.arraycopy(samples, 0, sorted, 0, n);
            Arrays.sort(sorted);
            int idx = (int) Math.ceil(p / 100.0 * n) - 1;
            return sorted[Math.max(0, Math.min(idx, n - 1))];
        }
    }
}
