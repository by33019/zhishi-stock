package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@link AiTaskStore} 的 MyBatis 实现：**哑存储**，不做任何业务判断。
 *
 * <p>它只负责三件事：把行拼成聚合、把聚合拆成行、把"影响了几行"如实翻译成布尔。
 * "影响 0 行意味着什么"（乐观锁冲突？已被人抢走执行权？）由用例层决定——
 * 在这里下结论会让同一个事实有两处解释。
 */
public class MyBatisAiTaskStore implements AiTaskStore {

    private final AiTaskMapper mapper;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public MyBatisAiTaskStore(AiTaskMapper mapper, LongSupplier idGenerator, Clock clock) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public Optional<AiTask> find(long taskId) {
        AiTaskRow row = mapper.find(taskId);
        return row == null ? Optional.empty() : Optional.of(toTask(row));
    }

    @Override
    public Optional<AiTask> findByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        AiTaskRow row = mapper.findByRequestId(requestId);
        return row == null ? Optional.empty() : Optional.of(toTask(row));
    }

    @Override
    public void insert(AiTask task) {
        mapper.insert(toRow(task));
        int sortNo = 0;
        for (AiContextTarget target : task.targets()) {
            mapper.insertTarget(new AiTaskTargetRow(
                    idGenerator.getAsLong(),
                    task.taskId(),
                    target.targetType(),
                    // 库里存的是 bigint 代理键。它必须已由 *IdentityProvider 解析出来；
                    // 为空说明调用方绕过了解析器（那是缺陷，不是可降级的情况）。
                    requireStorageId(target),
                    target.targetCode(),
                    target.targetName(),
                    target.targetRole(),
                    sortNo++));
        }
    }

    /**
     * {@code UPDATE ... WHERE id = ? AND version = ?}：影响 1 行才算写成功。
     *
     * <p>成功时把 {@code version + 1} 交回给调用方，因为推进是**数据库**做的
     * （{@code SET version = version + 1}）。不交回去的话，调用方手里那份会一直停在旧版本，
     * 下一次 {@code save} 匹配不到任何行，而它只会返回"冲突"——看起来像并发争抢，
     * 实际是自己拿错了版本。心跳与状态推进都会因此静默失效。
     */
    @Override
    public Optional<AiTask> save(AiTask task) {
        if (mapper.updateWithVersion(toRow(task)) != 1) {
            return Optional.empty();
        }
        return Optional.of(task.withVersion(task.version() + 1));
    }

    @Override
    public boolean claimForExecution(long taskId, OffsetDateTime at) {
        return mapper.claimForExecution(taskId, toLocalDateTime(at)) == 1;
    }

    @Override
    public boolean requestCancel(long taskId) {
        return mapper.requestCancel(taskId) == 1;
    }

    @Override
    public List<AiTask> findByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return mapper.findByUserAndStatuses(userId, statuses).stream().map(this::toTask).toList();
    }

    @Override
    public int countByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }
        return mapper.countByUserAndStatuses(userId, statuses);
    }

    @Override
    public int countCreatedSince(long userId, OffsetDateTime from) {
        return mapper.countCreatedSince(userId, toLocalDateTime(from));
    }

    @Override
    public List<AiTask> findRecoverable(
            OffsetDateTime queuedBefore, OffsetDateTime heartbeatBefore, int limit) {
        return mapper
                .findRecoverable(toLocalDateTime(queuedBefore), toLocalDateTime(heartbeatBefore), limit)
                .stream()
                .map(this::toTask)
                .toList();
    }

    // ---------- 行 ↔ 聚合 ----------

    private AiTask toTask(AiTaskRow row) {
        List<AiContextTarget> targets = new ArrayList<>();
        for (AiTaskTargetRow target : mapper.findTargets(row.taskId())) {
            targets.add(new AiContextTarget(
                    target.targetType(),
                    // 对外标识**不在库里**（表只存 bigint 代理键与代码快照）。
                    // 这里刻意留空，由 AiTaskService 经 *IdentityProvider 还原——
                    // 在存储层拼 "sim-" + code 就是第二份构词规则（M2-11 的教训）。
                    null,
                    target.targetCode(),
                    target.targetName(),
                    target.targetRole(),
                    target.targetId()));
        }
        return new AiTask(
                row.taskId(),
                row.requestId(),
                row.sessionId(),
                row.userId(),
                row.retryOfTaskId(),
                row.scene(),
                row.question(),
                toOffsetDateTime(row.analysisStartAt()),
                toOffsetDateTime(row.analysisEndAt()),
                row.status(),
                row.cancelRequested(),
                row.attemptNo(),
                row.maxAttempts(),
                row.providerCode(),
                row.modelCode(),
                row.traceId(),
                toOffsetDateTime(row.createdAt()),
                toOffsetDateTime(row.queuedAt()),
                toOffsetDateTime(row.startedAt()),
                toOffsetDateTime(row.firstChunkAt()),
                toOffsetDateTime(row.validatingAt()),
                toOffsetDateTime(row.heartbeatAt()),
                toOffsetDateTime(row.deadlineAt()),
                toOffsetDateTime(row.completedAt()),
                row.errorCategory(),
                row.errorCode(),
                row.errorMessage(),
                row.version(),
                targets);
    }

    private AiTaskRow toRow(AiTask task) {
        return new AiTaskRow(
                task.taskId(),
                task.requestId(),
                task.sessionId(),
                task.userId(),
                task.retryOfTaskId(),
                task.scene(),
                task.question(),
                toLocalDateTime(task.analysisStartAt()),
                toLocalDateTime(task.analysisEndAt()),
                task.status(),
                task.attemptNo(),
                task.maxAttempts(),
                task.cancelRequested(),
                task.providerCode(),
                task.modelCode(),
                task.traceId(),
                toLocalDateTime(task.createdAt()),
                toLocalDateTime(task.queuedAt()),
                toLocalDateTime(task.startedAt()),
                toLocalDateTime(task.firstChunkAt()),
                toLocalDateTime(task.validatingAt()),
                toLocalDateTime(task.heartbeatAt()),
                toLocalDateTime(task.deadlineAt()),
                toLocalDateTime(task.completedAt()),
                task.errorCategory(),
                task.errorCode(),
                task.errorMessage(),
                task.version());
    }

    private static long requireStorageId(AiContextTarget target) {
        if (target.storageId() == null) {
            throw new IllegalArgumentException(
                    "目标缺少持久化代理键，无法落库：" + target.targetType() + " " + target.targetCode());
        }
        return target.storageId();
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
