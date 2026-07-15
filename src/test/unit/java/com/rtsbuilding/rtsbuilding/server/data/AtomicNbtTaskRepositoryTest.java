package com.rtsbuilding.rtsbuilding.server.data;

import com.rtsbuilding.rtsbuilding.server.task.TaskType;
import com.rtsbuilding.rtsbuilding.server.task.identity.SubmissionId;
import com.rtsbuilding.rtsbuilding.server.task.identity.TaskId;
import com.rtsbuilding.rtsbuilding.server.task.persistence.AtomicNbtTaskRepository;
import com.rtsbuilding.rtsbuilding.server.task.persistence.TaskCodec;
import com.rtsbuilding.rtsbuilding.server.task.persistence.TaskLifecycleState;
import com.rtsbuilding.rtsbuilding.server.task.persistence.TaskRepository;
import com.rtsbuilding.rtsbuilding.server.task.persistence.TaskSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicNbtTaskRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void readResultDistinguishesMissingFoundEmptyAndCorrupt() throws Exception {
        Path missingPath = tempDir.resolve("missing.dat");
        AtomicNbtTaskRepository missing = repository(missingPath);
        assertInstanceOf(TaskRepository.LoadResult.Missing.class, missing.load());

        Path emptyPath = tempDir.resolve("found-empty.dat");
        assertTrue(new RtsAtomicNbtStore(emptyPath, "test/found-empty").write(new CompoundTag()));
        AtomicNbtTaskRepository foundEmpty = repository(emptyPath);
        assertInstanceOf(TaskRepository.LoadResult.Failed.class, foundEmpty.load(),
                "存在但缺 schema 的空 Root 不能降级为 Missing");

        Path corruptPath = tempDir.resolve("corrupt.dat");
        Files.write(corruptPath, new byte[]{1, 9, 4, 7, 2});
        AtomicNbtTaskRepository corrupt = repository(corruptPath);
        assertInstanceOf(TaskRepository.LoadResult.Failed.class, corrupt.load());

        Path validPath = tempDir.resolve("valid-empty.dat");
        TaskCodec codec = new TaskCodec();
        assertTrue(new RtsAtomicNbtStore(validPath, "test/valid-empty")
                .write(codec.encodeImage(TaskRepository.Image.empty())));
        assertInstanceOf(TaskRepository.LoadResult.Found.class, repository(validPath).load());
    }

    @Test
    void fullRootWriteOnlyStartsAfterPreparedCommitReachesWriterPhase() {
        Path file = tempDir.resolve("two-phase.dat");
        AtomicNbtTaskRepository repository = repository(file);
        assertInstanceOf(TaskRepository.LoadResult.Missing.class, repository.load());
        TaskSnapshot snapshot = snapshot();

        TaskRepository.PrepareResult.Prepared prepared = assertInstanceOf(
                TaskRepository.PrepareResult.Prepared.class,
                repository.prepare(TaskRepository.Commit.upserts(List.of(snapshot))));
        assertFalse(Files.exists(file), "prepare 不能在主线程写全 Root");

        TaskRepository.WriteCompletion completion = repository.writePrepared(prepared.commit());
        assertTrue(completion.successful());
        assertTrue(Files.exists(file));
        TaskRepository.AcknowledgeResult ack = repository.acknowledge(completion);
        assertTrue(ack.accepted());
        assertTrue(ack.durable());

        TaskRepository.LoadResult.Found reloaded = assertInstanceOf(
                TaskRepository.LoadResult.Found.class, repository(file).load());
        assertTrue(reloaded.image().tasks().containsKey(snapshot.id()));
    }

    private AtomicNbtTaskRepository repository(Path path) {
        return new AtomicNbtTaskRepository(new RtsAtomicNbtStore(path, "test/" + path.getFileName()),
                new TaskCodec());
    }

    private static TaskSnapshot snapshot() {
        CompoundTag payload = new CompoundTag();
        payload.putString("plan_ref", "test");
        return new TaskSnapshot(TaskId.create(), SubmissionId.create(), UUID.randomUUID(),
                "minecraft:overworld", TaskType.PLACEMENT, TaskLifecycleState.QUEUED,
                1, null, 1L, 10L, 10L, 4, 0, 0, 0, payload);
    }
}
