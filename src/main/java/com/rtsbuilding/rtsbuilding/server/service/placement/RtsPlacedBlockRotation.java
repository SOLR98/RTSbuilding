package com.rtsbuilding.rtsbuilding.server.service.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Server-authoritative resolver for rotating an already placed block.
 *
 * <p>The client supplies only a property name and serialized value. Both are
 * looked up again on the current server state, and only direction, axis, and
 * vanilla 16-segment rotation properties are accepted.
 */
public final class RtsPlacedBlockRotation {
    private static final String CREATE_KINETIC_BLOCK_ENTITY =
            "com.simibubi.create.content.kinetics.base.KineticBlockEntity";

    private RtsPlacedBlockRotation() {
    }

    public static boolean setProperty(
            ServerLevel level, BlockPos pos, String propertyName, String valueName) {
        if (!canReadNeighborhood(level, pos)
                || propertyName == null || propertyName.isBlank()
                || valueName == null || valueName.isBlank()) {
            return false;
        }

        BlockState current = level.getBlockState(pos);
        if (current.isAir() || isUnsafeState(current)) {
            return false;
        }

        Property<?> property = findProperty(current, propertyName);
        if (property == null || !isAllowedProperty(property)) {
            return false;
        }

        Optional<?> parsedValue = property.getValue(valueName);
        if (parsedValue.isEmpty()) {
            return false;
        }

        BlockState requested = setValue(current, property, parsedValue.get());
        return applyResolvedState(level, pos, current, requested);
    }

    static boolean canReadNeighborhood(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            if (!level.hasChunkAt(pos.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    static boolean applyResolvedState(
            ServerLevel level, BlockPos pos, BlockState current, BlockState requested) {
        if (level == null || pos == null || current == null || requested == null
                || current.getBlock() != requested.getBlock()
                || isUnsafeState(current) || isUnsafeState(requested)) {
            return false;
        }

        BlockState adjusted = Block.updateFromNeighbourShapes(requested, level, pos);
        if (adjusted.getBlock() != current.getBlock()
                || isUnsafeState(adjusted)
                || !adjusted.canSurvive(level, pos)
                || adjusted.equals(current)) {
            return false;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null && !blockEntity.getType().isValid(adjusted)) {
            return false;
        }

        boolean changed;
        if (blockEntity != null && isCreateKineticBlockEntity(blockEntity)) {
            changed = switchCreateKineticState(level, pos, adjusted);
        } else {
            if (blockEntity != null && !isVanillaBlock(current)) {
                // Unknown modded block entities can own capabilities or networks
                // that a plain setBlock cannot safely rebuild.
                return false;
            }
            changed = level.setBlock(pos, adjusted, Block.UPDATE_ALL);
        }
        if (!changed
                || !level.getBlockState(pos).equals(adjusted)
                || (blockEntity != null
                && (level.getBlockEntity(pos) != blockEntity || blockEntity.isRemoved()))) {
            return false;
        }
        return true;
    }

    private static Property<?> findProperty(BlockState state, String propertyName) {
        String normalized = propertyName.trim();
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(normalized)) {
                return property;
            }
        }
        return null;
    }

    private static boolean isAllowedProperty(Property<?> property) {
        Class<?> valueClass = property.getValueClass();
        return valueClass == Direction.class
                || valueClass == Direction.Axis.class
                || property == BlockStateProperties.ROTATION_16;
    }

    private static boolean isUnsafeState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BedBlock
                || block instanceof DoorBlock
                || block instanceof DoublePlantBlock
                || block instanceof MovingPistonBlock
                || block instanceof PistonHeadBlock) {
            return true;
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
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

    private static boolean isVanillaBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return "minecraft".equals(id.getNamespace());
    }

    private static boolean isCreateKineticBlockEntity(BlockEntity blockEntity) {
        Class<?> type = blockEntity.getClass();
        while (type != null) {
            if (CREATE_KINETIC_BLOCK_ENTITY.equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    /**
     * Create must detach/rejoin kinetic networks while changing state. Reflection
     * keeps Create optional while following its public static adapter when present.
     */
    private static boolean switchCreateKineticState(
            ServerLevel level, BlockPos pos, BlockState adjusted) {
        try {
            Class<?> kineticType = Class.forName(
                    CREATE_KINETIC_BLOCK_ENTITY,
                    false,
                    RtsPlacedBlockRotation.class.getClassLoader());
            Method switchMethod = kineticType.getMethod(
                    "switchToBlockState",
                    Level.class,
                    BlockPos.class,
                    BlockState.class);
            switchMethod.invoke(null, level, pos, adjusted);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException
                 | IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setValue(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }
}
