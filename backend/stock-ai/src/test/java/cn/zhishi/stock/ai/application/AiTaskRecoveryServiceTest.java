package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEvent;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskQueueMessage;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 恢复扫描测试。
 *
 * <p>它守着一类**只会表现为"任务卡住"**的缺陷：执行者在半路死掉后，
 * 任务既不会失败也不会完成，前端会一直显示"正在生成分析"。
 *
 * <p>另一条同样重要的：**次数用尽的任务不能再被投出去**。
 * 数据库的 {@code ck_ai_task_attempts (attempt_no <= max_attempts)} 会让
 * 第 3 次抢占直接报 SQL 错误——那不是"任务失败"，是整个扫描批次崩掉。
 */
class AiTaskRecoveryServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final long TASK_ID = 7001L;

    private final StubTaskStore tasks = new StubTaskStore();
    private final RecordingEventStream events = new RecordingEventStream();
    private final RecordingQueue queue = new RecordingQueue();

    private AiTaskRecoveryService service() {
        return new AiTaskRecoveryService(
                tasks, events, queue, CLOCK, 5, 5, 50);
    }

    @Test
    @DisplayName("队列消息丢了：QUEUED 且已过排队时限 → 重新投递，状态与尝试次数都不变")
    void reEnqueuesLostQueueMessage() {
        tasks.recoverable.add(task(AiTaskStatus.QUEUED, 0, false));

        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.requeued()).isEqualTo(1);
        assertThat(queue.enqueued).containsExactly(TASK_ID);
        assertThat(tasks.saved).isEmpty();
        assertThat(events.byTask).isEmpty();
    }

    @Test
    @DisplayName("执行者死了但还有尝试次数：回到 QUEUED 并重新入队")
    void requeuesAfterExecutorDeath() {
        tasks.recoverable.add(task(AiTaskStatus.RUNNING, 1, false));

        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.requeued()).isEqualTo(1);
        assertThat(tasks.saved).hasSize(1);
        assertThat(tasks.saved.get(0).status()).isEqualTo(AiTaskStatus.QUEUED);
        assertThat(tasks.saved.get(0).startedAt()).isNull();
        assertThat(queue.enqueued).containsExactly(TASK_ID);
    }

    @Test
    @DisplayName("尝试次数已用尽：置 TIMED_OUT 而**不是**再次投递（否则会撞 CHECK 约束）")
    void timesOutWhenAttemptsExhausted() {
        tasks.recoverable.add(task(AiTaskStatus.RUNNING, 2, false));

        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.timedOut()).isEqualTo(1);
        assertThat(tasks.saved.get(0).status()).isEqualTo(AiTaskStatus.TIMED_OUT);
        assertThat(tasks.saved.get(0).errorCode()).isEqualTo("AI_TASK_TIMED_OUT");
        assertThat(queue.enqueued).isEmpty();
        assertThat(events.typesOf()).containsExactly(AiTaskEventType.ERROR, AiTaskEventType.DONE);
    }

    @Test
    @DisplayName("已置取消意图：兑现为 CANCELED，不再投递")
    void honorsCancelRequested() {
        tasks.recoverable.add(task(AiTaskStatus.QUEUED, 0, true));

        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.canceled()).isEqualTo(1);
        assertThat(tasks.saved.get(0).status()).isEqualTo(AiTaskStatus.CANCELED);
        assertThat(tasks.saved.get(0).completedAt()).isNotNull();
        assertThat(queue.enqueued).isEmpty();
        assertThat(events.typesOf()).containsExactly(AiTaskEventType.STATUS, AiTaskEventType.DONE);
    }

    @Test
    @DisplayName("超过截止时间：优先判超时，不再重投（重投只会再超时一次）")
    void timesOutPastDeadlineBeforeRequeue() {
        AiTask expired = task(AiTaskStatus.RUNNING, 0, false);
        tasks.recoverable.add(withDeadline(expired, OffsetDateTime.now(CLOCK).minusSeconds(1)));

        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.timedOut()).isEqualTo(1);
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    @DisplayName("扫描为空：三个计数都是 0，不产生任何写入")
    void reportsNothingToDo() {
        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.scanned()).isZero();
        assertThat(report.requeued()).isZero();
        assertThat(report.canceled()).isZero();
        assertThat(report.timedOut()).isZero();
        assertThat(tasks.saved).isEmpty();
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    @DisplayName("乐观锁冲突：不重复投递（另一个实例已经在处理它）")
    void doesNotRequeueWhenSaveConflicts() {
        tasks.recoverable.add(task(AiTaskStatus.RUNNING, 1, false));
        tasks.saveSucceeds = false;

        AiTaskRecoveryService.RecoveryReport report = service().recover();

        assertThat(report.requeued()).isZero();
        assertThat(queue.enqueued).isEmpty();
    }

    // ---------- 夹具 ----------

    private static AiTask task(AiTaskStatus status, int attemptNo, boolean cancelRequested) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new AiTask(
                TASK_ID,
                "11111111-2222-3333-4444-555555555555",
                5001L,
                1001L,
                null,
                AiScene.STOCK,
                "怎么看",
                now.minusDays(1),
                now,
                status,
                cancelRequested,
                attemptNo,
                2,
                "SIMULATED",
                "sim-analyst-v1",
                "trace-1",
                now.minusMinutes(10),
                now.minusMinutes(10),
                now.minusMinutes(9),
                null,
                null,
                now.minusMinutes(9),
                now.plusSeconds(60),
                null,
                null,
                null,
                null,
                0,
                List.of(new AiContextTarget(
                        AiTargetType.SECURITY,
                        "sim-600519",
                        "600519",
                        "模拟证券600519",
                        AiTargetRole.PRIMARY,
                        600519L)));
    }

    private static AiTask withDeadline(AiTask task, OffsetDateTime deadline) {
        return new AiTask(
                task.taskId(), task.requestId(), task.sessionId(), task.userId(),
                task.retryOfTaskId(), task.scene(), task.question(),
                task.analysisStartAt(), task.analysisEndAt(), task.status(),
                task.cancelRequested(), task.attemptNo(), task.maxAttempts(),
                task.providerCode(), task.modelCode(), task.traceId(),
                task.createdAt(), task.queuedAt(), task.startedAt(), task.firstChunkAt(),
                task.validatingAt(), task.heartbeatAt(), deadline, task.completedAt(),
                task.errorCategory(), task.errorCode(), task.errorMessage(),
                task.version(), task.targets());
    }

    // ---------- 桩 ----------

    private static final class StubTaskStore implements AiTaskStore {
        final List<AiTask> recoverable = new ArrayList<>();
        final List<AiTask> saved = new ArrayList<>();
        boolean saveSucceeds = true;

        @Override
        public List<AiTask> findRecoverable(
                OffsetDateTime queuedBefore, OffsetDateTime heartbeatBefore, int limit) {
            return List.copyOf(recoverable);
        }

        @Override
        public Optional<AiTask> save(AiTask task) {
            if (!saveSucceeds) {
                return Optional.empty();
            }
            saved.add(task);
            return Optional.of(task.withVersion(task.version() + 1));
        }

        @Override
        public Optional<AiTask> find(long taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<AiTask> findByRequestId(String requestId) {
            return Optional.empty();
        }

        @Override
        public void insert(AiTask task) {
        }

        @Override
        public boolean claimForExecution(long taskId, OffsetDateTime at) {
            return false;
        }

        @Override
        public boolean requestCancel(long taskId) {
            return false;
        }

        @Override
        public List<AiTask> findByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
            return List.of();
        }

        @Override
        public int countByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
            return 0;
        }

        @Override
        public int countCreatedSince(long userId, OffsetDateTime from) {
            return 0;
        }
    }

    private static final class RecordingQueue implements AiTaskQueue {
        final List<Long> enqueued = new ArrayList<>();

        @Override
        public void enqueue(long taskId) {
            enqueued.add(taskId);
        }

        @Override
        public List<AiTaskQueueMessage> receive(int count) {
            return List.of();
        }

        @Override
        public void ack(String messageId) {
        }
    }

    private static final class RecordingEventStream implements AiTaskEventStream {
        final Map<Long, List<AiTaskEvent>> byTask = new LinkedHashMap<>();

        @Override
        public AiTaskEvent append(
                long taskId, AiTaskEventType type, LongFunction<String> payloadBuilder) {
            List<AiTaskEvent> list = byTask.computeIfAbsent(taskId, key -> new ArrayList<>());
            long sequence = list.size() + 1L;
            AiTaskEvent event =
                    new AiTaskEvent(sequence, type, payloadBuilder.apply(sequence));
            list.add(event);
            return event;
        }

        @Override
        public List<AiTaskEvent> readAfter(long taskId, long lastSequence, int count) {
            return List.of();
        }

        @Override
        public Optional<Long> latestSequence(long taskId) {
            return Optional.empty();
        }

        List<AiTaskEventType> typesOf() {
            return byTask.values().stream()
                    .flatMap(List::stream)
                    .map(AiTaskEvent::type)
                    .sorted(java.util.Comparator.comparingInt(Enum::ordinal))
                    .toList();
        }
    }

}
