package com.rtsbuilding.rtsbuilding.server.task.persistence;

import com.rtsbuilding.rtsbuilding.server.task.identity.TaskId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * durable task 的持久化端口。
 *
 * <p>一次 {@link Commit} 必须满足全有或全无。主线程只调用 {@link #prepare(Commit)}；后台 writer
 * 只接收 {@link PreparedCommit} 并调用 {@link #writePrepared(PreparedCommit)}；完成消息回到主线程后
 * 才调用 {@link #acknowledge(WriteCompletion)}。后台阶段不得接触玩家、世界、Capability 或 Session。</p>
 */
public interface TaskRepository {

    LoadResult load();

    PrepareResult prepare(Commit commit);

    /** 仅限有界后台 writer 调用；实现只能操作深复制 NBT、byte[]、Path 和普通值。 */
    WriteCompletion writePrepared(PreparedCommit preparedCommit);

    /** 仅限服务器主线程消费 writer completion。 */
    AcknowledgeResult acknowledge(WriteCompletion completion);

    /** 已持久化的完整逻辑镜像；集合在构造时做防御性复制。 */
    record Image(Map<TaskId, TaskSnapshot> tasks,
                 Map<TaskId, TaskTombstone> tombstones,
                 Set<String> completedMigrations) {
        public Image {
            tasks = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(tasks, "tasks")));
            tombstones = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(tombstones, "tombstones")));
            completedMigrations = Set.copyOf(
                    new LinkedHashSet<>(Objects.requireNonNull(completedMigrations, "completedMigrations")));
        }

        public static Image empty() {
            return new Image(Map.of(), Map.of(), Set.of());
        }
    }

    /** 一个必须原子提交的增量批次。 */
    record Commit(List<TaskSnapshot> upserts,
                  List<TaskTombstone> tombstones,
                  Set<TaskId> purgedTombstones,
                  Set<String> completedMigrations) {
        public Commit {
            upserts = List.copyOf(new ArrayList<>(Objects.requireNonNull(upserts, "upserts")));
            tombstones = List.copyOf(new ArrayList<>(Objects.requireNonNull(tombstones, "tombstones")));
            purgedTombstones = Set.copyOf(
                    new LinkedHashSet<>(Objects.requireNonNull(purgedTombstones, "purgedTombstones")));
            completedMigrations = Set.copyOf(
                    new LinkedHashSet<>(Objects.requireNonNull(completedMigrations, "completedMigrations")));
            if (upserts.isEmpty() && tombstones.isEmpty()
                    && purgedTombstones.isEmpty() && completedMigrations.isEmpty()) {
                throw new IllegalArgumentException("不能提交空批次");
            }
        }

        public static Commit upserts(Collection<TaskSnapshot> snapshots) {
            return new Commit(List.copyOf(snapshots), List.of(), Set.of(), Set.of());
        }

        public int recordCount() {
            return upserts.size() + tombstones.size() + purgedTombstones.size();
        }
    }

    /** Repository 自有的不透明准备结果；接口不暴露内部 NBT，避免外部线程修改。 */
    interface PreparedCommit {
        UUID ticketId();

        int recordCount();
    }

    sealed interface LoadResult permits LoadResult.Found, LoadResult.Missing, LoadResult.Failed {
        record Found(Image image) implements LoadResult {
            public Found {
                Objects.requireNonNull(image, "image");
            }
        }

        record Missing() implements LoadResult {
        }

        record Failed(Throwable cause) implements LoadResult {
            public Failed {
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    sealed interface PrepareResult permits PrepareResult.Prepared, PrepareResult.Failed {
        record Prepared(PreparedCommit commit) implements PrepareResult {
            public Prepared {
                Objects.requireNonNull(commit, "commit");
            }
        }

        record Failed(Throwable cause) implements PrepareResult {
            public Failed {
                Objects.requireNonNull(cause, "cause");
            }
        }
    }

    record WriteCompletion(UUID ticketId, boolean successful, long bytesWritten, Throwable failure) {
        public WriteCompletion {
            Objects.requireNonNull(ticketId, "ticketId");
            if (bytesWritten < -1L) throw new IllegalArgumentException("bytesWritten 无效");
            if (successful == (failure != null)) {
                throw new IllegalArgumentException("成功 completion 不能带 failure，失败 completion 必须带 failure");
            }
        }

        public static WriteCompletion succeeded(UUID ticketId, long bytesWritten) {
            return new WriteCompletion(ticketId, true, bytesWritten, null);
        }

        public static WriteCompletion failed(UUID ticketId, Throwable failure) {
            return new WriteCompletion(ticketId, false, -1L, failure);
        }
    }

    record AcknowledgeResult(boolean accepted, boolean durable, Throwable failure) {
        public AcknowledgeResult {
            if (!accepted && durable) throw new IllegalArgumentException("未接受的 ACK 不能是 durable");
            if (durable && failure != null) throw new IllegalArgumentException("durable ACK 不能带 failure");
        }
    }
}
