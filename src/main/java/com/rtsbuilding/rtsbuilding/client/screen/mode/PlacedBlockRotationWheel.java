package com.rtsbuilding.rtsbuilding.client.screen.mode;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.rtsbuilding.rtsbuilding.client.util.RtsClientUiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界中已放置方块的方向选择轮盘。
 *
 * <p>候选只来自 Minecraft 的标准 facing/axis 属性；真正的合法性判断和状态
 * 应用仍由服务端依据当前方块重新完成。轮盘中的方块模型使用 RTS 相机的角度
 * 渲染，让玩家看到的是选定后从当前摄影机方向观察到的外观。</p>
 */
public final class PlacedBlockRotationWheel {
    private static final int OPTION_DISTANCE = 68;
    private static final int OPTION_SIZE = 44;
    private static final int INNER_RADIUS = 26;
    private static final int OUTER_RADIUS = 102;
    private static final int EDGE_PADDING = 116;

    private final List<RotationProperty> properties = new ArrayList<>();
    private boolean open;
    private int centerX;
    private int centerY;
    private int selectedPropertyIndex;
    private BlockPos targetPos;
    private Block targetBlock;
    private float cameraYaw;
    private float cameraPitch;

    public boolean isOpen() {
        return this.open;
    }

    public BlockPos targetPos() {
        return this.targetPos;
    }

    /**
     * 为目标状态建立方向候选。返回 false 表示该方块没有窄白名单内的可旋转属性。
     */
    public boolean open(
            BlockState state,
            BlockPos pos,
            double mouseX,
            double mouseY,
            int screenWidth,
            int screenHeight,
            float cameraYaw,
            float cameraPitch) {
        close();
        if (state == null || pos == null || state.isAir() || isUnsafeState(state)) {
            return false;
        }

        addProperty(state, BlockStateProperties.FACING);
        addProperty(state, BlockStateProperties.FACING_HOPPER);
        addProperty(state, BlockStateProperties.HORIZONTAL_FACING);
        addProperty(state, BlockStateProperties.AXIS);
        addProperty(state, BlockStateProperties.HORIZONTAL_AXIS);
        if (this.properties.isEmpty()) {
            return false;
        }

        int maxX = Math.max(EDGE_PADDING, screenWidth - EDGE_PADDING);
        int maxY = Math.max(EDGE_PADDING, screenHeight - EDGE_PADDING);
        this.centerX = Mth.clamp((int) Math.round(mouseX), EDGE_PADDING, maxX);
        this.centerY = Mth.clamp((int) Math.round(mouseY), EDGE_PADDING, maxY);
        this.targetPos = pos.immutable();
        this.targetBlock = state.getBlock();
        this.cameraYaw = cameraYaw;
        this.cameraPitch = cameraPitch;
        this.selectedPropertyIndex = 0;
        this.open = true;
        return true;
    }

    public void close() {
        this.open = false;
        this.properties.clear();
        this.selectedPropertyIndex = 0;
        this.targetPos = null;
        this.targetBlock = null;
    }

    /** 与服务端首版策略一致：未知模组方块实体不进入通用 setBlock 路径。 */
    public static boolean supportsBlockEntity(Level level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return true;
        }
        if ("minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())) {
            return true;
        }
        Class<?> type = blockEntity.getClass();
        while (type != null) {
            if ("com.simibubi.create.content.kinetics.base.KineticBlockEntity".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /** 目标方块被替换时立即关掉轮盘，避免把选择应用到后来出现的另一方块。 */
    public boolean targetStillMatches(Level level) {
        return this.open
                && level != null
                && this.targetPos != null
                && level.hasChunkAt(this.targetPos)
                && level.getBlockState(this.targetPos).is(this.targetBlock);
    }

    public RotationChoice hoveredChoice(double mouseX, double mouseY) {
        if (!this.open || this.properties.isEmpty()) {
            return null;
        }
        RotationProperty property = selectedProperty();
        int size = property.options().size();
        if (size == 0) {
            return null;
        }
        double dx = mouseX - this.centerX;
        double dy = mouseY - this.centerY;
        double distanceSquared = dx * dx + dy * dy;
        if (distanceSquared < INNER_RADIUS * INNER_RADIUS
                || distanceSquared > OUTER_RADIUS * OUTER_RADIUS) {
            return null;
        }
        double angle = Math.atan2(dy, dx) + Math.PI / 2.0D;
        if (angle < 0.0D) {
            angle += Math.PI * 2.0D;
        }
        double sector = Math.PI * 2.0D / size;
        int index = Math.floorMod((int) Math.floor((angle + sector / 2.0D) / sector), size);
        RotationOption option = property.options().get(index);
        return new RotationChoice(this.targetPos, property.propertyName(), option.valueName());
    }

    /** 多个方向属性（极少数模组方块）可用滚轮切换。 */
    public boolean cycleProperty(double scrollY) {
        if (!this.open || this.properties.size() < 2 || scrollY == 0.0D) {
            return this.open;
        }
        int direction = scrollY > 0.0D ? -1 : 1;
        this.selectedPropertyIndex = Math.floorMod(
                this.selectedPropertyIndex + direction,
                this.properties.size());
        return true;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        if (!this.open || this.properties.isEmpty()) {
            return;
        }
        RotationProperty property = selectedProperty();
        RotationChoice hovered = hoveredChoice(mouseX, mouseY);
        fillCircle(graphics, this.centerX, this.centerY, 91, 0xD0161B22);
        fillCircle(graphics, this.centerX, this.centerY, 28, 0xF0242C36);

        int optionCount = property.options().size();
        Lighting.setupFor3DItems();
        RenderSystem.enableDepthTest();
        for (int i = 0; i < optionCount; i++) {
            RotationOption option = property.options().get(i);
            double angle = -Math.PI / 2.0D + (Math.PI * 2.0D * i / optionCount);
            int optionX = this.centerX + (int) Math.round(Math.cos(angle) * OPTION_DISTANCE);
            int optionY = this.centerY + (int) Math.round(Math.sin(angle) * OPTION_DISTANCE);
            boolean isHovered = hovered != null && hovered.valueName().equals(option.valueName());
            drawOption(graphics, font, option, optionX, optionY, isHovered);
        }
        graphics.flush();
        RenderSystem.disableDepthTest();
        Lighting.setupForFlatItems();

        String propertyLabel = Component.translatable(propertyLabelKey(property.propertyName())).getString();
        RtsClientUiUtil.drawCenteredStringNoShadow(
                graphics, font, propertyLabel, this.centerX, this.centerY - 9, 0xFFFFFFFF);
        if (this.properties.size() > 1) {
            RtsClientUiUtil.drawCenteredStringNoShadow(
                    graphics,
                    font,
                    Component.translatable(
                            "screen.rtsbuilding.rotation_wheel.property",
                            this.selectedPropertyIndex + 1,
                            this.properties.size()).getString(),
                    this.centerX,
                    this.centerY + 3,
                    0xFFB9C6D3);
        }
        RtsClientUiUtil.drawCenteredStringNoShadow(
                graphics,
                font,
                Component.translatable("screen.rtsbuilding.rotation_wheel.hint").getString(),
                this.centerX,
                this.centerY + 106,
                0xFFD6DFEA);
    }

    private void drawOption(
            GuiGraphics graphics,
            Font font,
            RotationOption option,
            int centerX,
            int centerY,
            boolean hovered) {
        int x = centerX - OPTION_SIZE / 2;
        int y = centerY - OPTION_SIZE / 2;
        int border = hovered ? 0xFFFFD878 : option.current() ? 0xFF8FD4A8 : 0xFF657587;
        int background = hovered ? 0xFF5A4720 : option.current() ? 0xFF244E38 : 0xEE1D242C;
        graphics.fill(x, y, x + OPTION_SIZE, y + OPTION_SIZE, border);
        graphics.fill(x + 2, y + 2, x + OPTION_SIZE - 2, y + OPTION_SIZE - 2, background);

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY + 4, 180.0F);
        pose.scale(22.0F, -22.0F, 22.0F);
        pose.mulPose(Axis.XP.rotationDegrees(25.0F - Mth.clamp(this.cameraPitch, -45.0F, 45.0F) * 0.35F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F + this.cameraYaw));
        pose.translate(-0.5F, -0.5F, -0.5F);
        try {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    option.state(),
                    pose,
                    graphics.bufferSource(),
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException ignored) {
            // A broken third-party model must not make the RTS screen unusable.
        } finally {
            pose.popPose();
        }

        RtsClientUiUtil.drawCenteredStringNoShadow(
                graphics,
                font,
                optionLabel(option.value()).getString(),
                centerX,
                y + OPTION_SIZE - 9,
                0xFFFFFFFF);
    }

    private RotationProperty selectedProperty() {
        return this.properties.get(this.selectedPropertyIndex);
    }

    private <T extends Comparable<T>> void addProperty(BlockState state, Property<T> property) {
        if (!state.hasProperty(property)) {
            return;
        }
        // FACING 与 FACING_HOPPER 的序列化名称相同；同一状态中只会命中其中之一。
        if (this.properties.stream().anyMatch(entry -> entry.propertyName().equals(property.getName()))) {
            return;
        }
        T current = state.getValue(property);
        List<RotationOption> options = new ArrayList<>();
        for (T value : property.getPossibleValues()) {
            options.add(new RotationOption(
                    property.getName(value),
                    value,
                    state.setValue(property, value),
                    value.equals(current)));
        }
        if (options.size() > 1) {
            this.properties.add(new RotationProperty(property.getName(), List.copyOf(options)));
        }
    }

    private static String propertyLabelKey(String propertyName) {
        return propertyName != null && propertyName.contains("axis")
                ? "screen.rtsbuilding.rotation_wheel.axis"
                : "screen.rtsbuilding.rotation_wheel.facing";
    }

    private static Component optionLabel(Comparable<?> value) {
        if (value instanceof Direction direction) {
            return Component.translatable("screen.rtsbuilding.rotation_wheel.direction."
                    + direction.getName());
        }
        if (value instanceof Direction.Axis axis) {
            return Component.translatable("screen.rtsbuilding.rotation_wheel.axis."
                    + axis.getName());
        }
        return Component.literal(String.valueOf(value));
    }

    private static boolean isUnsafeState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BedBlock
                || block instanceof DoorBlock
                || block instanceof DoublePlantBlock
                || block instanceof MovingPistonBlock
                || block instanceof PistonHeadBlock
                || state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                || state.hasProperty(BlockStateProperties.BED_PART)) {
            return true;
        }
        if (block instanceof ChestBlock
                && state.hasProperty(BlockStateProperties.CHEST_TYPE)
                && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE) {
            return true;
        }
        return block instanceof PistonBaseBlock
                && state.hasProperty(BlockStateProperties.EXTENDED)
                && state.getValue(BlockStateProperties.EXTENDED);
    }

    private static void fillCircle(
            GuiGraphics graphics,
            int centerX,
            int centerY,
            int radius,
            int color) {
        for (int y = -radius; y <= radius; y++) {
            int halfWidth = (int) Math.sqrt(radius * radius - y * y);
            graphics.fill(
                    centerX - halfWidth,
                    centerY + y,
                    centerX + halfWidth + 1,
                    centerY + y + 1,
                    color);
        }
    }

    private record RotationProperty(String propertyName, List<RotationOption> options) {
    }

    private record RotationOption(
            String valueName,
            Comparable<?> value,
            BlockState state,
            boolean current) {
    }

    public record RotationChoice(BlockPos pos, String propertyName, String valueName) {
    }
}
