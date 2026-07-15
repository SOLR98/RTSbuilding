package com.rtsbuilding.rtsbuilding.server.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止后续改动悄悄恢复第二套 Tick runtime 或预算外实体全扫描。 */
class UnifiedLongTaskRuntimeContractTest {
    private static final Path MAIN = Path.of("src/main/java/com/rtsbuilding/rtsbuilding");

    @Test
    void legacyTickRuntimeClassesStayDeleted() {
        Path core = MAIN.resolve("server/pipeline/core");
        assertFalse(Files.exists(core.resolve("TickablePipelineRegistry.java")));
        assertFalse(Files.exists(core.resolve("ActivePipeline.java")));
        assertFalse(Files.exists(core.resolve("TickablePipe.java")));
        assertFalse(Files.exists(MAIN.resolve("server/pipeline/mining/UltimineTickPipe.java")));
    }

    @Test
    void orchestratorHasOnlyOneLongTaskRuntime() throws IOException {
        String source = read("server/service/ServerTickOrchestrator.java");
        assertTrue(source.contains("RtsTaskEngine.INSTANCE.tick(server)"));
        assertFalse(source.contains("TickablePipelineRegistry"));
        assertFalse(source.contains("funnel().tick"));
        assertFalse(source.contains("RtsPlacedRecoveryService.tick"));
    }

    @Test
    void funnelEntityQueryHasHardResultLimit() throws IOException {
        String source = read("server/service/impl/RtsFunnelServiceImpl.java");
        assertTrue(source.contains("EntityTypeTest.forClass(ItemEntity.class)"));
        assertTrue(source.contains("drops, queryLimit"));
        assertFalse(source.contains("getEntitiesOfClass("));
    }

    @Test
    void recoveryQueueStoresEntityIdentityInsteadOfCopiedStacks() throws IOException {
        String state = read("server/storage/state/RtsPlacementState.java");
        String service = read("server/service/RtsPlacedRecoveryService.java");
        assertTrue(state.contains("Deque<UUID> entityIds"));
        assertTrue(service.contains("droppedEntity.getUUID()"));
        assertFalse(service.contains("stacks.addLast(droppedStack.copy())"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
