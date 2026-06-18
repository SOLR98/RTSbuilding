package com.rtsbuilding.rtsbuilding.client.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.network.RtsClientPacketGateway;
import com.rtsbuilding.rtsbuilding.client.record.StorageEntry;
import com.rtsbuilding.rtsbuilding.client.service.RtsClientItemManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * 客户端全方位压测命令。
 *
 * <p>模拟客户端高并发操作：批量链接容器、快速拾取/合成/挖掘/交互。
 * 所有操作通过正常网络通道发送，测量服务端在高负载下的真实处理能力。
 */
public final class RtsClientStressCommand {

    private static final AtomicBoolean active = new AtomicBoolean(false);
    private static final ConcurrentLinkedQueue<Runnable> pendingOps = new ConcurrentLinkedQueue<>();
    private static final AtomicLong totalSent = new AtomicLong();
    private static final AtomicInteger tickCounter = new AtomicInteger();
    private static long startTimeMs;
    private static long lastReportTimeMs;
    private static long lastReportCount;

    private RtsClientStressCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("rtsclient")
                .then(literal("stress")
                        .then(literal("batchlink")
                                .then(argument("radius", IntegerArgumentType.integer(1, 64))
                                        .executes(RtsClientStressCommand::batchLink)))
                        .then(literal("pickup")
                                .then(argument("intervalMs", IntegerArgumentType.integer(1, 1000))
                                        .then(argument("durationSeconds", IntegerArgumentType.integer(1, 300))
                                                .executes(RtsClientStressCommand::rapidPickup))))
                        .then(literal("craft")
                                .then(argument("intervalMs", IntegerArgumentType.integer(1, 1000))
                                        .then(argument("durationSeconds", IntegerArgumentType.integer(1, 300))
                                                .executes(RtsClientStressCommand::rapidCraft))))
                        .then(literal("mine")
                                .then(argument("intervalMs", IntegerArgumentType.integer(1, 500))
                                        .executes(RtsClientStressCommand::rapidMine)))
                        .then(literal("interact")
                                .then(argument("intervalMs", IntegerArgumentType.integer(1, 500))
                                        .executes(RtsClientStressCommand::rapidInteract)))
                        .then(literal("stop")
                                .executes(RtsClientStressCommand::stopStress))
                        .then(literal("status")
                                .executes(RtsClientStressCommand::statusStress)));
    }

    /**
     * 由客户端 tick 调用，处理排队的压测操作。
     */
    public static void tickClient() {
        if (!active.get()) return;

        long now = System.currentTimeMillis();
        tickCounter.incrementAndGet();

        // 每秒输出统计
        if (now - lastReportTimeMs >= 1000) {
            long count = totalSent.get();
            long delta = count - lastReportCount;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "[RTSClient Stress] " + delta + " ops/s | 累计 " + count + " | q=" + pendingOps.size()), true);
            }
            lastReportCount = count;
            lastReportTimeMs = now;
        }

        // 处理队列中的操作（限制每 tick 处理量，避免客户端卡死）
        int processed = 0;
        Runnable op;
        while ((op = pendingOps.poll()) != null && processed < 100) {
            op.run();
            totalSent.incrementAndGet();
            processed++;
        }
    }

    public static boolean isActive() {
        return active.get();
    }

    // ======================================================================
    //  batchlink — 批量链接容器
    // ======================================================================

    private static int batchLink(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            sendMsg(ctx, "需要进入游戏");
            return 0;
        }
        if (!hasRtsActive(ctx)) return 0;

        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        BlockPos center = BlockPos.containing(player.position());
        List<BlockPos> found = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockEntity be = player.level().getBlockEntity(pos);
                    if (be instanceof ChestBlockEntity) {
                        found.add(pos.immutable());
                    }
                }
            }
        }

        if (found.isEmpty()) {
            sendMsg(ctx, "半径 " + radius + " 内没有找到容器");
            return 0;
        }

        RtsClientPacketGateway.sendBatchLinkStorage(found, true);
        final int total = found.size();
        sendMsg(ctx, "批量链接发送: " + total + " 个容器（" +
                ((total + 255) / 256) + " 批）");
        return total;
    }

    // ======================================================================
    //  rapidPickup — 高频拾取
    // ======================================================================

    private static int rapidPickup(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientRtsController controller = ClientRtsController.get();
        if (!hasRtsActive(ctx)) return 0;
        if (controller == null || controller.getStorageEntries().isEmpty()) {
            sendMsg(ctx, "请先打开存储浏览器加载物品列表");
            return 0;
        }

        int intervalMs = IntegerArgumentType.getInteger(ctx, "intervalMs");
        int durationSeconds = IntegerArgumentType.getInteger(ctx, "durationSeconds");
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        List<StorageEntry> entries = new ArrayList<>(controller.getStorageEntries());
        Collections.shuffle(entries);

        startStress(ctx, intervalMs, endTime, () -> {
            int idx = (int) (System.currentTimeMillis() / intervalMs) % entries.size();
            StorageEntry entry = entries.get(idx);
            RtsClientItemManager.INSTANCE.pickupFromLinked(entry.stack(), 1);
        });

        return 1;
    }

    // ======================================================================
    //  rapidCraft — 高频合成
    // ======================================================================

    private static int rapidCraft(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientRtsController controller = ClientRtsController.get();
        if (!hasRtsActive(ctx)) return 0;
        if (controller == null || controller.getCraftableEntries().isEmpty()) {
            sendMsg(ctx, "请先打开合成终端加载配方列表");
            return 0;
        }

        int intervalMs = IntegerArgumentType.getInteger(ctx, "intervalMs");
        int durationSeconds = IntegerArgumentType.getInteger(ctx, "durationSeconds");
        long endTime = System.currentTimeMillis() + durationSeconds * 1000L;

        List<String> recipeIds = new ArrayList<>();
        for (var entry : controller.getCraftableEntries()) {
            recipeIds.add(entry.recipeId());
        }
        Collections.shuffle(recipeIds);

        startStress(ctx, intervalMs, endTime, () -> {
            int idx = (int) (System.currentTimeMillis() / intervalMs) % recipeIds.size();
            RtsClientPacketGateway.sendCraftRecipe(recipeIds.get(idx), 1);
        });

        return 1;
    }

    // ======================================================================
    //  rapidMine — 高频挖掘
    // ======================================================================

    private static int rapidMine(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!hasRtsActive(ctx)) return 0;
        if (player == null) {
            sendMsg(ctx, "需要进入游戏");
            return 0;
        }

        int intervalMs = IntegerArgumentType.getInteger(ctx, "intervalMs");
        long endTime = System.currentTimeMillis() + 30_000L; // 默认30秒

        // 收集玩家面前半径5格内的非空气方块
        BlockPos center = BlockPos.containing(player.position());
        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!player.level().getBlockState(pos).isAir()) {
                        targets.add(pos.immutable());
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            sendMsg(ctx, "面前无可挖掘方块");
            return 0;
        }
        Collections.shuffle(targets);

        final List<BlockPos> finalTargets = targets;
        startStress(ctx, intervalMs, endTime, () -> {
            int idx = (int) (System.currentTimeMillis() / intervalMs) % finalTargets.size();
            BlockPos target = finalTargets.get(idx);
            RtsClientPacketGateway.sendMineStart(target, 0, 0,
                    "", net.minecraft.world.item.ItemStack.EMPTY, false, false);
        });

        return 1;
    }

    // ======================================================================
    //  rapidInteract — 高频交互
    // ======================================================================

    private static int rapidInteract(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (!hasRtsActive(ctx)) return 0;
        if (player == null) {
            sendMsg(ctx, "需要进入游戏");
            return 0;
        }

        int intervalMs = IntegerArgumentType.getInteger(ctx, "intervalMs");
        long endTime = System.currentTimeMillis() + 30_000L;

        BlockPos center = BlockPos.containing(player.position());
        List<BlockPos> targets = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!player.level().getBlockState(pos).isAir()) {
                        targets.add(pos.immutable());
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            sendMsg(ctx, "面前无可交互方块");
            return 0;
        }
        Collections.shuffle(targets);

        final List<BlockPos> finalTargets = targets;
        Vec3 origin = new Vec3(center.getX(), center.getY() + player.getEyeHeight(), center.getZ());
        startStress(ctx, intervalMs, endTime, () -> {
            int idx = (int) (System.currentTimeMillis() / intervalMs) % finalTargets.size();
            BlockPos target = finalTargets.get(idx);
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(target), net.minecraft.core.Direction.UP, target, false);
            RtsClientPacketGateway.sendInteractBlockWithPinnedItem(
                    hit, RtsClientItemManager.INSTANCE.getPinnedItemId(), origin,
                    new Vec3(0, -1, 0));
        });

        return 1;
    }

    // ======================================================================
    //  stop / status
    // ======================================================================

    private static int stopStress(CommandContext<CommandSourceStack> ctx) {
        if (!active.get()) {
            sendMsg(ctx, "没有正在运行的压测");
            return 0;
        }
        long elapsed = System.currentTimeMillis() - startTimeMs;
        long total = totalSent.get();
        double opsPerSec = total / Math.max(elapsed / 1000.0, 0.001);

        active.set(false);
        pendingOps.clear();

        final long finalTotal = total;
        final double finalOps = opsPerSec;
        final double finalSec = elapsed / 1000.0;
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("压测停止 | 总耗时 %.1fs | 总操作 %d | 平均 %.0f ops/s",
                        finalSec, finalTotal, finalOps)), false);

        Minecraft.getInstance().player.displayClientMessage(Component.literal(
                String.format("压测停止: %d ops in %.1fs (%.0f ops/s)", total, finalSec, opsPerSec)), false);
        return 1;
    }

    private static int statusStress(CommandContext<CommandSourceStack> ctx) {
        if (!active.get()) {
            sendMsg(ctx, "压测未运行。使用 /rtsclient stress <模式> 启动");
            return 0;
        }
        long elapsed = System.currentTimeMillis() - startTimeMs;
        long total = totalSent.get();
        double opsPerSec = total / Math.max(elapsed / 1000.0, 0.001);

        final long finalTotal = total;
        final double finalOps = opsPerSec;
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("压测运行中 | 已运行 %.1fs | %d ops | %.0f ops/s | 队列 %d",
                        elapsed / 1000.0, finalTotal, finalOps, pendingOps.size())), false);
        return 1;
    }

    // ======================================================================
    //  Internal
    // ======================================================================

    private static void startStress(CommandContext<CommandSourceStack> ctx, int intervalMs,
                                     long endTime, Runnable task) {
        if (active.get()) {
            sendMsg(ctx, "已有压测在运行，请先 /rtsclient stress stop");
            return;
        }

        startTimeMs = System.currentTimeMillis();
        lastReportTimeMs = startTimeMs;
        lastReportCount = 0;
        totalSent.set(0);
        tickCounter.set(0);
        active.set(true);

        long taskCount = (endTime - startTimeMs) / intervalMs;
        sendMsg(ctx, "压测启动: " + taskCount + " 次操作 | 间隔 " + intervalMs + "ms");

        // 在后台线程中按间隔生成操作，放入队列
        Thread worker = new Thread(() -> {
            long nextTime = System.currentTimeMillis();
            while (active.get() && System.currentTimeMillis() < endTime) {
                pendingOps.add(task);
                nextTime += intervalMs;
                long sleepMs = nextTime - System.currentTimeMillis();
                if (sleepMs > 0) {
                    try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
                }
            }
            if (active.get()) {
                active.set(false);
                Minecraft.getInstance().execute(() -> {
                    long elapsed = System.currentTimeMillis() - startTimeMs;
                    long total = totalSent.get();
                    var player = Minecraft.getInstance().player;
                    if (player != null) {
                        player.displayClientMessage(Component.literal(
                                "压测完成: " + total + " ops in " + (elapsed / 1000.0) + "s"), false);
                    }
                });
            }
        }, "RTS-Client-Stress");
        worker.setDaemon(true);
        worker.start();
    }

    private static void sendMsg(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal("[RTSClient Stress] " + msg), false);
    }

    private static boolean hasRtsActive(CommandContext<CommandSourceStack> ctx) {
        ClientRtsController controller = ClientRtsController.get();
        if (controller == null || !controller.isEnabled()) {
            ctx.getSource().sendFailure(Component.literal("请先启用 RTS 模式 (按键 G)"));
            return false;
        }
        return true;
    }
}
