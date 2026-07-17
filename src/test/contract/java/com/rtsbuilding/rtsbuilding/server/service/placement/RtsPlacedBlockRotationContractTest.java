package com.rtsbuilding.rtsbuilding.server.service.placement;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtsPlacedBlockRotationContractTest {
    @Test
    void payloadCarriesOnlyBoundedPropertyAndValueNames() throws Exception {
        String payload = source(
                "src/main/java/com/rtsbuilding/rtsbuilding/network/builder/C2SRtsRotateBlockPayload.java");

        assertTrue(payload.contains("String propertyName, String valueName"));
        assertTrue(payload.contains("writeUtf(payload.propertyName(), MAX_PROPERTY_NAME_CHARS)"));
        assertTrue(payload.contains("writeUtf(payload.valueName(), MAX_VALUE_NAME_CHARS)"));
        assertTrue(payload.contains("readUtf(MAX_PROPERTY_NAME_CHARS)"));
        assertTrue(payload.contains("readUtf(MAX_VALUE_NAME_CHARS)"));
        assertFalse(payload.contains("BlockState"));
    }

    @Test
    void serverReparsesAWhitelistedPropertyFromCurrentState() throws Exception {
        String rotation = source(
                "src/main/java/com/rtsbuilding/rtsbuilding/server/service/placement/RtsPlacedBlockRotation.java");

        assertTrue(rotation.contains("BlockState current = level.getBlockState(pos);"));
        assertTrue(rotation.contains("Property<?> property = findProperty(current, propertyName);"));
        assertTrue(rotation.contains("property.getValue(valueName)"));
        assertTrue(rotation.contains("valueClass == Direction.class"));
        assertTrue(rotation.contains("valueClass == Direction.Axis.class"));
        assertTrue(rotation.contains("property == BlockStateProperties.ROTATION_16"));
        assertFalse(rotation.contains("BuiltInRegistries.BLOCK.get("),
                "The payload must never select a block type from the registry");
    }

    @Test
    void applicationRejectsUnsafeOrUnloadedStatesAndRevalidatesNeighbors() throws Exception {
        String rotation = source(
                "src/main/java/com/rtsbuilding/rtsbuilding/server/service/placement/RtsPlacedBlockRotation.java");
        String implementation = source(
                "src/main/java/com/rtsbuilding/rtsbuilding/server/service/impl/RtsPlacementServiceImpl.java");
        String handler = source(
                "src/main/java/com/rtsbuilding/rtsbuilding/network/builder/handler/RtsPlaceHandlers.java");

        assertTrue(handler.contains("context.enqueueWork("));
        assertTrue(handler.contains(
                "payload.propertyName().isBlank() && payload.valueName().isBlank()"),
                "Only the explicit legacy empty/empty payload may use clockwise fallback");
        assertTrue(implementation.contains("RtsProgressionManager.canUse(player, RtsFeature.ROTATE_BLOCK)"));
        assertTrue(implementation.contains("registry.session().getIfPresent(player)"));
        assertTrue(implementation.contains("session.mode != com.rtsbuilding.rtsbuilding.common.build.BuilderMode.ROTATE"));
        assertTrue(implementation.contains("player.isSpectator()"));
        assertTrue(implementation.contains("!player.mayBuild()"));
        assertTrue(implementation.contains("RtsLinkedStorageResolver.canAccessWorldTarget(player, pos)"));
        assertTrue(implementation.contains("RtsClaimProtectionService.canInteractBlock("));
        assertTrue(rotation.contains("level.hasChunkAt(pos)"));
        assertTrue(rotation.contains("level.hasChunkAt(pos.relative(direction))"));
        assertTrue(rotation.contains("Block.updateFromNeighbourShapes(requested, level, pos)"));
        assertTrue(rotation.contains("adjusted.getBlock() != current.getBlock()"));
        assertTrue(rotation.contains("!adjusted.canSurvive(level, pos)"));
        assertTrue(rotation.contains("level.setBlock(pos, adjusted, Block.UPDATE_ALL)"));
        assertTrue(rotation.contains("block instanceof BedBlock"));
        assertTrue(rotation.contains("block instanceof DoorBlock"));
        assertTrue(rotation.contains("ChestType.SINGLE"));
        assertTrue(rotation.contains("block instanceof PistonBaseBlock"));
        assertTrue(rotation.contains("state.getValue(BlockStateProperties.EXTENDED)"));
        assertTrue(rotation.contains("!isVanillaBlock(current)"));
        assertTrue(rotation.contains("switchCreateKineticState(level, pos, adjusted)"));
        assertTrue(rotation.contains("level.getBlockEntity(pos) != blockEntity"));
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
