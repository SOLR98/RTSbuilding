package com.rtsbuilding.rtsbuilding.server.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.Arrays;
import java.util.stream.Collectors;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * RTS 测试与调试命令。
 * <p>
 * 仓库层级结构：
 * <ul>
 *   <li>水平分区：每 {@link #ZONE_SIZE} 行用彩色混凝土标记一个区域</li>
 *   <li>垂直分层：通过 Y 轴楼层堆叠，每层间隔 {@link #FLOOR_GAP} 格</li>
 * </ul>
 * 预设尺寸：
 * <pre>
 * tiny   2× 2×1      4 双箱
 * small  5× 2×1     10 双箱
 * mini   6× 5×1     30 双箱
 * medium 10×10×2   100 双箱×2层
 * big    12×20×2   240 双箱×2层
 * large  20×50×3  1000 双箱×3层
 * huge   30×50×3  1500 双箱×3层
 * mega   50×50×4  2500 双箱×4层
 * </pre>
 */
public final class RtsCommand {

    private static final int LANE_WIDTH = 4;
    private static final int FLOOR_GAP = 4;
    private static final int ZONE_SIZE = 5;
    private static final Block[] ZONE_MARKERS = {
            Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE,
            Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE,
            Blocks.PURPLE_CONCRETE, Blocks.ORANGE_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.MAGENTA_CONCRETE
    };

    private enum WarehouseSize {
        TINY(2, 2, 1),
        SMALL(5, 2, 1),
        MINI(6, 5, 1),
        MEDIUM(10, 10, 2),
        BIG(12, 20, 2),
        LARGE(20, 50, 3),
        HUGE(30, 50, 3),
        MEGA(50, 50, 4);

        final int rows, cols, floors;

        WarehouseSize(int rows, int cols, int floors) {
            this.rows = rows;
            this.cols = cols;
            this.floors = floors;
        }
    }

    private RtsCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return literal("rts")
                .requires(source -> source.hasPermission(2))
                .then(literal("gentestwarehouse")
                        .then(argument("size", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (WarehouseSize s : WarehouseSize.values()) {
                                        builder.suggest(s.name().toLowerCase());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(RtsCommand::genTestWarehouse))
                        .then(argument("rows", IntegerArgumentType.integer(1, 200))
                                .then(argument("cols", IntegerArgumentType.integer(1, 100))
                                        .then(argument("floors", IntegerArgumentType.integer(1, 10))
                                                .executes(RtsCommand::genTestWarehouseCustom))
                                        .executes(RtsCommand::genTestWarehouseCustom))))
                .then(RtsStressCommand.build());
    }

    private static int genTestWarehouse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String sizeName = StringArgumentType.getString(ctx, "size").toUpperCase();
        WarehouseSize size;
        try {
            size = WarehouseSize.valueOf(sizeName);
        } catch (IllegalArgumentException e) {
            String validNames = Arrays.stream(WarehouseSize.values())
                    .map(s -> s.name().toLowerCase())
                    .collect(Collectors.joining("|"));
            ctx.getSource().sendFailure(Component.literal("用法: /rts gentestwarehouse " + validNames));
            return 0;
        }
        return buildWarehouse(ctx, size.rows, size.cols, size.floors);
    }

    private static int genTestWarehouseCustom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int rows = IntegerArgumentType.getInteger(ctx, "rows");
        int cols = IntegerArgumentType.getInteger(ctx, "cols");
        int floors;
        try {
            floors = IntegerArgumentType.getInteger(ctx, "floors");
        } catch (IllegalArgumentException e) {
            floors = 1;
        }
        return buildWarehouse(ctx, rows, cols, floors);
    }

    private static int buildWarehouse(CommandContext<CommandSourceStack> ctx, int rows, int cols, int floors)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos origin = BlockPos.containing(player.position());
        BlockPos start = origin.relative(Direction.NORTH, 3);

        int total = 0;

        for (int floor = 0; floor < floors; floor++) {
            int baseY = origin.getY() + (floor * FLOOR_GAP);

            for (int r = 0; r < rows; r++) {
                if (r > 0 && r % ZONE_SIZE == 0) {
                    int zoneIdx = ((r / ZONE_SIZE) - 1) % ZONE_MARKERS.length;
                    Block markerBlock = ZONE_MARKERS[zoneIdx];
                    BlockPos zoneMarker = start.west(1).atY(baseY).north(r * LANE_WIDTH);
                    level.setBlock(zoneMarker, markerBlock.defaultBlockState(), 3);
                }

                for (int c = 0; c < cols; c++) {
                    BlockPos pos = start.atY(baseY);
                    BlockPos main = pos.east(c * 2).north(r * LANE_WIDTH);
                    BlockPos neighbor = main.east(1);

                    BlockState chestState = Blocks.CHEST.defaultBlockState()
                            .setValue(ChestBlock.FACING, Direction.NORTH)
                            .setValue(ChestBlock.TYPE, ChestType.RIGHT);
                    BlockState neighborState = Blocks.CHEST.defaultBlockState()
                            .setValue(ChestBlock.FACING, Direction.NORTH)
                            .setValue(ChestBlock.TYPE, ChestType.LEFT);

                    level.setBlock(main, chestState, 3);
                    level.setBlock(neighbor, neighborState, 3);
                    total++;
                }
            }

            BlockPos floorMarker = start.atY(baseY)
                    .east(cols + 1)
                    .north((rows * LANE_WIDTH) / 2);
            level.setBlock(floorMarker, Blocks.GLOWSTONE.defaultBlockState(), 3);
        }

        int finalTotal = total;
        int containerCount = finalTotal * 2;
        String floorInfo = floors > 1 ? "【" + floors + "层】" : "";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "已生成测试仓库" + floorInfo + ": " + rows + "行×" + cols + "列 = " + finalTotal +
                "个双箱子（" + containerCount + "个容器），总槽位数 ≈ " + (containerCount * 27)), true);

        return finalTotal;
    }
}
