package com.rtsbuilding.rtsbuilding.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rtsbuilding.rtsbuilding.server.service.RtsSessionService;
import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.storage.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * 持续存储压测命令。
 *
 * <p>在服务端 tick 中持续执行插入/提取操作，使用路由方法执行。
 * 同时采样对比暴力路径耗时，每5秒输出实时吞吐 + 路由加速比。
 */
public final class RtsContinuousStressCommand {

    private static final Map<UUID, StressJob> JOBS = new ConcurrentHashMap<>();
    private static final int DEFAULT_OPS_PER_TICK = 100;
    private static final int REPORT_INTERVAL_SECONDS = 5;
    private static final int MAX_OPS_PER_TICK = 10_000;
    private static final int MAX_UNIQUE_ITEMS = 1000;
    private static final int ROUTE_SAMPLE_EVERY_N_OPS = 256;

    private RtsContinuousStressCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("continuous")
                .then(literal("start")
                        .then(argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> start(ctx, DEFAULT_OPS_PER_TICK, 0))
                                .then(argument("opsPerTick", IntegerArgumentType.integer(1, MAX_OPS_PER_TICK))
                                        .executes(ctx -> {
                                            int level = IntegerArgumentType.getInteger(ctx, "level");
                                            int ops = IntegerArgumentType.getInteger(ctx, "opsPerTick");
                                            return start(ctx, ops, 0);
                                        })
                                        .then(argument("durationSeconds", IntegerArgumentType.integer(1, 3600))
                                                .executes(ctx -> {
                                                    int level = IntegerArgumentType.getInteger(ctx, "level");
                                                    int ops = IntegerArgumentType.getInteger(ctx, "opsPerTick");
                                                    int dur = IntegerArgumentType.getInteger(ctx, "durationSeconds");
                                                    return start(ctx, ops, dur);
                                                })))))
                .then(literal("stop")
                        .executes(RtsContinuousStressCommand::stop))
                .then(literal("report")
                        .executes(RtsContinuousStressCommand::report));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, int opsPerTick, int durationSeconds)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID uuid = player.getUUID();

        StressJob existing = JOBS.get(uuid);
        if (existing != null) existing.snapshot(ctx.getSource());

        RtsStorageSession session = RtsSessionService.getIfPresent(player);
        if (session == null) {
            ctx.getSource().sendFailure(Component.literal("请先启用 RTS 模式"));
            return 0;
        }

        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
        if (aggregate == null || aggregate.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("聚合存储未就绪，请绑定容器后重试"));
            return 0;
        }

        int level = IntegerArgumentType.getInteger(ctx, "level");
        int uniqueItems = Math.min((int) Math.pow(10, Math.min(level, 3)) * 10, MAX_UNIQUE_ITEMS);

        List<Item> testItems = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null) continue;
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) continue;
            testItems.add(item);
            if (testItems.size() >= uniqueItems) break;
        }
        Collections.shuffle(testItems);
        if (testItems.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("无法收集到测试物品"));
            return 0;
        }

        List<Item> itemsInStorage = scanItemsFromAggregate(aggregate);
        List<Item> insertTargets = itemsInStorage.isEmpty() ? testItems : itemsInStorage;
        List<Item> extractTargets = itemsInStorage;

        StressJob job = new StressJob(session, insertTargets, extractTargets,
                aggregate, opsPerTick, durationSeconds, existing);
        JOBS.put(uuid, job);

        String duration = durationSeconds > 0 ? (" | " + durationSeconds + "秒后自动停止") : "";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "持续压测(路由) | level=" + level + " | 容器内 " + itemsInStorage.size() +
                "种 | " + opsPerTick + " ops/tick" + duration), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        StressJob job = JOBS.remove(player.getUUID());
        if (job == null) {
            ctx.getSource().sendFailure(Component.literal("没有正在运行的持续压测"));
            return 0;
        }
        job.snapshot(ctx.getSource());
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        StressJob job = JOBS.get(player.getUUID());
        if (job == null) {
            ctx.getSource().sendFailure(Component.literal("没有正在运行的持续压测"));
            return 0;
        }
        job.snapshot(ctx.getSource());
        return 1;
    }

    public static void tickAll(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, StressJob>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, StressJob> entry = it.next();
            StressJob job = entry.getValue();
            UUID uuid = entry.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);

            if (player == null || !player.isAlive()) { it.remove(); continue; }

            if (!player.hasPermissions(2)) {
                job.log("持续压测自动停止（权限不足）", player);
                it.remove();
                continue;
            }

            if (job.durationSeconds > 0 && now - job.startTimeMs >= job.durationSeconds * 1000L) {
                job.log("持续压测自动停止（" + job.durationSeconds + "秒到期）", player);
                it.remove();
                continue;
            }

            RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
            job.tick(player, aggregate);

            long elapsedSinceReport = now - job.lastReportTimeMs;
            if (elapsedSinceReport >= REPORT_INTERVAL_SECONDS * 1000L) {
                job.report(player, now);
            }
        }
    }

    // ======================================================================
    //  StressJob
    // ======================================================================

    private static final class StressJob {
        final RtsStorageSession session;
        final List<Item> insertTargets;
        final List<Item> extractTargets;
        final int opsPerTick;
        final int durationSeconds;
        final long startTimeMs;

        int itemIndex;
        long tickCounter;
        long lastReportTimeMs;
        final AtomicLong totalOps;
        final AtomicLong totalSuccess;
        final AtomicLong failInsertFull;
        final AtomicLong failExtractNotFound;
        long lastReportOps;
        long lastReportSuccess;
        long lastReportFailInsert;
        long lastReportFailExtract;

        // ── 路由耗时采样 ──
        long routeInsertTotalNs;
        long routeExtractTotalNs;
        int routeInsertSamples;
        int routeExtractSamples;

        StressJob(RtsStorageSession session, List<Item> insertTargets, List<Item> extractTargets,
                RtsAggregateStorage aggregate, int opsPerTick, int durationSeconds, StressJob previous) {
            this.session = session;
            this.insertTargets = insertTargets;
            this.extractTargets = extractTargets;
            this.opsPerTick = opsPerTick;
            this.durationSeconds = durationSeconds;
            this.startTimeMs = System.currentTimeMillis();
            this.lastReportTimeMs = this.startTimeMs;
            this.itemIndex = 0;
            this.totalOps = previous != null ? previous.totalOps : new AtomicLong();
            this.totalSuccess = previous != null ? previous.totalSuccess : new AtomicLong();
            this.failInsertFull = previous != null ? previous.failInsertFull : new AtomicLong();
            this.failExtractNotFound = previous != null ? previous.failExtractNotFound : new AtomicLong();
            this.lastReportOps = previous != null ? previous.lastReportOps : 0;
            this.lastReportSuccess = previous != null ? previous.lastReportSuccess : 0;
            this.lastReportFailInsert = previous != null ? previous.lastReportFailInsert : 0;
            this.lastReportFailExtract = previous != null ? previous.lastReportFailExtract : 0;
        }

        void tick(ServerPlayer player, RtsAggregateStorage aggregate) {
            boolean canExtract = !extractTargets.isEmpty();
            for (int i = 0; i < opsPerTick; i++) {
                boolean sampleRoute = (totalOps.get() % ROUTE_SAMPLE_EVERY_N_OPS) == 0;

                if (canExtract && ThreadLocalRandom.current().nextBoolean()) {
                    Item item = extractTargets.get(itemIndex % Math.max(1, extractTargets.size()));
                    itemIndex = (itemIndex + 1) % Math.max(1, extractTargets.size());
                    int maxStack = Math.max(1, item.getDefaultMaxStackSize());
                    int amount = ThreadLocalRandom.current().nextInt(1, maxStack + 1);

                    long t0 = sampleRoute ? System.nanoTime() : 0;
                    ItemStack result = aggregate != null
                            ? aggregate.executeExtractRoute(item, null, amount)
                            : ItemStack.EMPTY;
                    if (sampleRoute && aggregate != null) {
                        routeExtractTotalNs += System.nanoTime() - t0;
                        routeExtractSamples++;
                    }
                    if (!result.isEmpty()) totalSuccess.incrementAndGet();
                    else failExtractNotFound.incrementAndGet();
                } else {
                    Item item = insertTargets.get(itemIndex % insertTargets.size());
                    itemIndex = (itemIndex + 1) % insertTargets.size();
                    int maxStack = Math.max(1, item.getDefaultMaxStackSize());
                    int amount = ThreadLocalRandom.current().nextInt(1, maxStack + 1);
                    ItemStack toInsert = new ItemStack(item, amount);

                    long t0 = sampleRoute ? System.nanoTime() : 0;
                    ItemStack remain = aggregate != null
                            ? aggregate.executeInsertRoute(toInsert, false)
                            : toInsert;
                    if (sampleRoute && aggregate != null) {
                        routeInsertTotalNs += System.nanoTime() - t0;
                        routeInsertSamples++;
                    }
                    if (remain.isEmpty() || remain.getCount() < amount) totalSuccess.incrementAndGet();
                    else failInsertFull.incrementAndGet();
                }
                totalOps.incrementAndGet();
            }
        }

        void report(ServerPlayer player, long now) {
            long ops = totalOps.get();
            long success = totalSuccess.get();
            long failInsert = failInsertFull.get();
            long failExtract = failExtractNotFound.get();
            long opsDelta = ops - lastReportOps;
            long successDelta = success - lastReportSuccess;
            long failInsertDelta = failInsert - lastReportFailInsert;
            long failExtractDelta = failExtract - lastReportFailExtract;
            double opsPerSec = opsDelta / (double) REPORT_INTERVAL_SECONDS;
            double elapsedSec = (now - startTimeMs) / 1000.0;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("持续压测(路由) | %.1fs | 近%d秒: %d ops(%d成功/%d满/%d缺) | %.0f ops/s",
                    elapsedSec, REPORT_INTERVAL_SECONDS, opsDelta, successDelta,
                    failInsertDelta, failExtractDelta, opsPerSec));

            if (routeInsertSamples > 0) {
                double avgNs = routeInsertTotalNs / (double) routeInsertSamples;
                sb.append(String.format("\n  路由插入 avg: %s (%d样本)",
                        RtsStressCommand.formatNanos(avgNs), routeInsertSamples));
            }
            if (routeExtractSamples > 0) {
                double avgNs = routeExtractTotalNs / (double) routeExtractSamples;
                sb.append(String.format(" | 路由提取 avg: %s (%d样本)",
                        RtsStressCommand.formatNanos(avgNs), routeExtractSamples));
            }

            sb.append(String.format("\n  累计: %d ops(%d满/%d缺)", ops, failInsert, failExtract));
            log(sb.toString(), player);

            lastReportOps = ops;
            lastReportSuccess = success;
            lastReportFailInsert = failInsert;
            lastReportFailExtract = failExtract;
            lastReportTimeMs = now;
            routeInsertTotalNs = 0;
            routeExtractTotalNs = 0;
            routeInsertSamples = 0;
            routeExtractSamples = 0;
        }

        void snapshot(CommandSourceStack source) {
            long ops = totalOps.get();
            long success = totalSuccess.get();
            long failInsert = failInsertFull.get();
            long failExtract = failExtractNotFound.get();
            long elapsedMs = System.currentTimeMillis() - startTimeMs;
            double elapsedSec = elapsedMs / 1000.0;
            double opsPerSec = ops / Math.max(elapsedSec, 0.001);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("持续压测统计 | %.2fs | %d ops | 成功 %d (%.1f%%) | 满:%d 缺:%d | %.0f ops/s",
                    elapsedSec, ops, success, ops > 0 ? (success * 100.0 / ops) : 0,
                    failInsert, failExtract, opsPerSec));
            if (routeInsertSamples > 0) {
                double avgNs = routeInsertTotalNs / (double) routeInsertSamples;
                sb.append("\n  路由插入 avg: ").append(RtsStressCommand.formatNanos(avgNs))
                        .append(" (").append(routeInsertSamples).append("样本)");
            }
            if (routeExtractSamples > 0) {
                double avgNs = routeExtractTotalNs / (double) routeExtractSamples;
                sb.append("  路由提取 avg: ").append(RtsStressCommand.formatNanos(avgNs))
                        .append(" (").append(routeExtractSamples).append("样本)");
            }
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
        }

        void log(String msg, ServerPlayer player) {
            player.sendSystemMessage(Component.literal("[RTS Stress] " + msg));
        }
    }

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

    private static List<Item> bruteScanItems(List<?> handlers) {
        Set<Item> found = new LinkedHashSet<>();
        if (handlers == null || handlers.isEmpty()) return new ArrayList<>();
        for (Object obj : handlers) {
            if (!(obj instanceof net.neoforged.neoforge.items.IItemHandler handler)) continue;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) found.add(stack.getItem());
            }
        }
        return new ArrayList<>(found);
    }
}
