package com.rtsbuilding.rtsbuilding.server.task.persistence;

import com.rtsbuilding.rtsbuilding.server.task.identity.SubmissionId;
import com.rtsbuilding.rtsbuilding.server.task.identity.TaskId;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskCodecTest {
    private final TaskCodec codec = new TaskCodec();

    @Test
    void fullImageRoundTripPreservesTaskPayloadTombstoneAndMigrationLedger() {
        TaskSnapshot task = TaskStoreTest.snapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), 31,
                TaskLifecycleState.WAITING_RESOURCE,
                new TaskWaitKey("item", "minecraft:stone"), 4L, "minecraft:overworld");
        TaskTombstone tombstone = new TaskTombstone(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                7L, TaskLifecycleState.COMPLETED, 200L, 1_000L);
        TaskRepository.Image source = new TaskRepository.Image(
                Map.of(task.id(), task), Map.of(tombstone.taskId(), tombstone), Set.of("session-jobs-v1"));

        TaskRepository.Image decoded = codec.decodeImage(codec.encodeImage(source));

        assertEquals(source, decoded);
    }

    @Test
    void payloadIsDeepCopiedAtConstructionAndReadBoundary() {
        CompoundTag sourcePayload = new CompoundTag();
        sourcePayload.putInt("cursor_blob", 4);
        TaskSnapshot task = new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.MINING,
                TaskLifecycleState.RUNNING, -1, null, 1L, 0L, 0L,
                10, 0, 0, 0, sourcePayload);

        sourcePayload.putInt("cursor_blob", 99);
        CompoundTag exposed = task.payload();
        exposed.putInt("cursor_blob", 77);

        assertEquals(4, task.payload().getInt("cursor_blob"));
    }

    @Test
    void unknownOrCorruptSchemaCannotBecomeEmptyRepository() {
        CompoundTag unknown = new CompoundTag();
        unknown.putInt("schema", TaskCodec.CURRENT_SCHEMA + 1);
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeImage(unknown));

        CompoundTag corrupt = new CompoundTag();
        corrupt.putInt("schema", TaskCodec.CURRENT_SCHEMA);
        net.minecraft.nbt.ListTag tasks = new net.minecraft.nbt.ListTag();
        CompoundTag missingIdentity = new CompoundTag();
        missingIdentity.putString("type", "PLACEMENT");
        tasks.add(missingIdentity);
        corrupt.put("tasks", tasks);
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeImage(corrupt));
    }

    @Test
    void rootListsAndPayloadRequireExactNbtTypes() {
        CompoundTag wrongRootList = new CompoundTag();
        wrongRootList.putInt("schema", TaskCodec.CURRENT_SCHEMA);
        wrongRootList.putString("tasks", "not-a-list");
        wrongRootList.put("tombstones", new net.minecraft.nbt.ListTag());
        wrongRootList.put("completed_migrations", new net.minecraft.nbt.ListTag());
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeImage(wrongRootList));

        CompoundTag wrongElementType = new CompoundTag();
        wrongElementType.putInt("schema", TaskCodec.CURRENT_SCHEMA);
        net.minecraft.nbt.ListTag stringTasks = new net.minecraft.nbt.ListTag();
        stringTasks.add(net.minecraft.nbt.StringTag.valueOf("not-a-task"));
        wrongElementType.put("tasks", stringTasks);
        wrongElementType.put("tombstones", new net.minecraft.nbt.ListTag());
        wrongElementType.put("completed_migrations", new net.minecraft.nbt.ListTag());
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeImage(wrongElementType));

        TaskSnapshot task = TaskStoreTest.snapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), 55,
                TaskLifecycleState.QUEUED, null, 1L, "minecraft:overworld");
        CompoundTag encoded = codec.encodeSnapshot(task);
        encoded.putInt("payload", 7);
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeSnapshot(encoded));
    }

    @Test
    void modifiedUtfLimitRejectsStringsThatFitCharCountButNotNbtBytes() {
        CompoundTag payload = new CompoundTag();
        payload.putString("text", "界".repeat(22_000));
        TaskSnapshot task = new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.PLACEMENT,
                TaskLifecycleState.QUEUED, -1, null, 1L, 0L, 0L,
                1, 0, 0, 0, payload);

        assertThrows(IllegalArgumentException.class, () -> codec.estimateSnapshotBytes(task));
    }

    @Test
    void migrationLedgerCapacityIsBounded() {
        CompoundTag root = new CompoundTag();
        root.putInt("schema", TaskCodec.CURRENT_SCHEMA);
        root.put("tasks", new net.minecraft.nbt.ListTag());
        root.put("tombstones", new net.minecraft.nbt.ListTag());
        net.minecraft.nbt.ListTag migrations = new net.minecraft.nbt.ListTag();
        for (int i = 0; i <= TaskCodec.MAX_MIGRATIONS; i++) {
            migrations.add(net.minecraft.nbt.StringTag.valueOf("migration-" + i));
        }
        root.put("completed_migrations", migrations);

        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeImage(root));
    }

    @Test
    void optionalWorkflowDefaultsOnlyWhenAbsentAndRejectsWrongType() {
        TaskSnapshot task = new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.PLACEMENT,
                TaskLifecycleState.QUEUED, -1, null, 1L, 0L, 0L,
                1, 0, 0, 0, new CompoundTag());
        CompoundTag absent = codec.encodeSnapshot(task);
        assertEquals(-1, codec.decodeSnapshot(absent).workflowEntryId());

        absent.putString("workflow", "wrong-type");
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeSnapshot(absent));
    }

    @Test
    void optionalWaitDefaultsOnlyWhenAbsentAndRejectsWrongType() {
        TaskSnapshot task = new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.MINING,
                TaskLifecycleState.QUEUED, -1, null, 1L, 0L, 0L,
                1, 0, 0, 0, new CompoundTag());
        CompoundTag absent = codec.encodeSnapshot(task);
        assertNull(codec.decodeSnapshot(absent).waitKey());

        absent.putString("wait", "wrong-type");
        assertThrows(TaskCodec.TaskCodecException.class, () -> codec.decodeSnapshot(absent));
    }

    @Test
    void dimensionMustBeCanonicalResourceLocationAndWaitKeyCountsTowardBudget() {
        assertThrows(IllegalArgumentException.class, () -> new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "Bad Dimension",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.MINING,
                TaskLifecycleState.QUEUED, -1, null, 1L, 0L, 0L,
                1, 0, 0, 0, new CompoundTag()));

        TaskSnapshot plain = new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.MINING,
                TaskLifecycleState.QUEUED, -1, null, 1L, 0L, 0L,
                1, 0, 0, 0, new CompoundTag());
        TaskSnapshot waiting = new TaskSnapshot(
                TaskId.create(), SubmissionId.create(), UUID.randomUUID(), "minecraft:overworld",
                com.rtsbuilding.rtsbuilding.server.task.TaskType.MINING,
                TaskLifecycleState.WAITING_RESOURCE, -1,
                new TaskWaitKey("item", "minecraft:oak_log"), 1L, 0L, 0L,
                1, 0, 0, 0, new CompoundTag());
        assertTrue(codec.estimateSnapshotBytes(waiting) > codec.estimateSnapshotBytes(plain));
    }
}
