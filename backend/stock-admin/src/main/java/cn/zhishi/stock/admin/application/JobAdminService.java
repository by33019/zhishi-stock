package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.JobDefinition;
import cn.zhishi.stock.admin.domain.JobDefinitionCatalog;
import cn.zhishi.stock.admin.domain.JobExecutionDispatcher;
import cn.zhishi.stock.admin.domain.JobTaskExecutor;
import cn.zhishi.stock.admin.domain.JobTrigger;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.job.JobExecution;
import cn.zhishi.stock.system.job.JobExecutionOutcome;
import cn.zhishi.stock.system.job.JobExecutionQuery;
import cn.zhishi.stock.system.job.JobExecutionRecorder;
import cn.zhishi.stock.system.job.JobExecutionRequest;
import cn.zhishi.stock.system.job.JobExecutionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 任务管理用例（契约 §16.3 ADM-JOB-01~05）。
 *
 * <h2>人工触发为什么不是事务</h2>
 * {@code trigger} 与 {@code retry} 刻意**不加** {@code @Transactional}。
 * 原因是那条 {@code RUNNING} 记录必须**先提交**，后台线程才可能读到它：
 * 若整段包在事务里，异步执行会在记录可见之前开始，表现为"回填影响 0 行"
 * （{@code complete} 的 {@code WHERE status = 'RUNNING'} 匹配不到）——
 * 于是记录永远停在运行中，而任务其实已经跑完了。
 *
 * <p>代价是 {@code retry} 里"读旧记录 → 算尝试号 → 写新记录"不再是原子的。
 * 这一段的正确性由数据库的唯一索引保证（{@code uk_job_execution_batch_shard_attempt}）：
 * 并发的两次重试会撞索引，其中一个拿到明确的失败，而不是两条同次记录。
 *
 * <h2>提交失败要自己收尾</h2>
 * 队列满或执行器关闭时，那条已经写下的 {@code RUNNING} 记录不会被任何回调回填。
 * 因此 {@link #dispatch} 在提交失败时把它记成 FAILED 再抛——"卡在运行中"的假象
 * 比一次明确的失败更难排查。
 */
public class JobAdminService {

    /** 队列拒绝的失败分类：与"任务自己跑失败"区分开，前者重试大概率立刻再失败一次。 */
    private static final String DISPATCH_REJECTED_CATEGORY = "DISPATCH_REJECTED";
    private static final String DISPATCH_REJECTED_CODE = "JOB_DISPATCH_REJECTED";

    private final JobDefinitionCatalog catalog;
    private final JobTaskExecutor executor;
    private final JobExecutionStore store;
    private final JobExecutionRecorder recorder;
    private final JobExecutionDispatcher dispatcher;
    private final Clock clock;

    public JobAdminService(
            JobDefinitionCatalog catalog,
            JobTaskExecutor executor,
            JobExecutionStore store,
            JobExecutionRecorder recorder,
            JobExecutionDispatcher dispatcher,
            Clock clock) {
        this.catalog = catalog;
        this.executor = executor;
        this.store = store;
        this.recorder = recorder;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /** ADM-JOB-01：白名单任务定义。 */
    public List<JobDefinition> definitions() {
        return catalog.all();
    }

    /** ADM-JOB-02：人工触发。返回时任务**尚未执行**（202 的语义）。 */
    public JobExecution trigger(
            String jobName, TriggerJobCommand command, long operatorId, String traceId) {
        JobDefinition definition = requireTriggerable(jobName);
        String scopeKey = resolveScopeKey(definition, command.scopeKey());
        int shardTotal = command.shardTotalOrDefault();
        if (shardTotal < 1) {
            throw AdminException.invalidRequest("shardTotal 必须 >= 1");
        }

        JobExecutionRequest request = JobExecutionRequest.manual(
                definition.jobName(),
                definition.handlerName(),
                newBatchId(),
                command.providerId(),
                shardTotal,
                traceId);
        JobTrigger trigger = new JobTrigger(
                definition.jobName(),
                scopeKey,
                command.providerId(),
                shardTotal,
                command.reason(),
                operatorId);

        return loaded(dispatch(request, trigger));
    }

    /** ADM-JOB-05：重试。新记录不覆盖旧记录，{@code batch_id} 沿用原批次。 */
    public JobExecution retry(long executionId, RetryJobCommand command, long operatorId, String traceId) {
        JobExecution previous = store.find(executionId)
                .orElseThrow(() -> AdminException.jobExecutionNotFound(executionId));
        if (!previous.retryable()) {
            throw AdminException.jobExecutionNotRetryable(executionId, previous.status().name());
        }
        // 原执行可能来自一个已经下线的任务名（白名单改过）。重试同样要走白名单校验，
        // 否则"某个任务被移出白名单"之后，它的历史记录仍然是一条可用的触发入口。
        JobDefinition definition = requireTriggerable(previous.jobName());

        int attemptNo = store.nextAttemptNo(
                previous.jobName(), previous.batchId(), previous.shardIndex());
        JobExecutionRequest request =
                JobExecutionRequest.retryOf(previous, attemptNo, traceId);
        JobTrigger trigger = new JobTrigger(
                previous.jobName(),
                // 作用范围没有落在执行记录里（本平台所有任务都是单范围），
                // 因此重试只能用任务声明的默认值，而不是假装恢复了"上次用的那个"。
                definition.defaultScopeKey(),
                previous.providerId(),
                previous.shardTotal(),
                command == null ? null : command.reason(),
                operatorId);

        return loaded(dispatch(request, trigger));
    }

    /** ADM-JOB-03：执行分页，按开始时间倒序。 */
    public PageData<JobExecution> list(JobExecutionQuery query) {
        JobExecutionQuery resolved = withResolvedRange(query);
        long total = store.count(resolved);
        List<JobExecution> items = total == 0 ? List.of() : store.page(resolved);
        return PageData.of(items, resolved.page(), resolved.size(), total);
    }

    /** ADM-JOB-04：执行详情。 */
    public JobExecution detail(long executionId) {
        return store.find(executionId)
                .orElseThrow(() -> AdminException.jobExecutionNotFound(executionId));
    }

    // ---------- 内部 ----------

    private long dispatch(JobExecutionRequest request, JobTrigger trigger) {
        long executionId = recorder.start(request);
        try {
            dispatcher.submit(() -> recorder.finish(executionId, () -> executor.execute(trigger)));
        } catch (RuntimeException exception) {
            store.complete(
                    executionId,
                    JobExecutionOutcome.failed(
                            DISPATCH_REJECTED_CATEGORY,
                            DISPATCH_REJECTED_CODE,
                            "执行队列不可用，任务未被执行：" + exception.getMessage()),
                    OffsetDateTime.now(clock));
            throw exception;
        }
        return executionId;
    }

    private JobExecution loaded(long executionId) {
        return store.find(executionId)
                .orElseThrow(() -> AdminException.jobExecutionNotFound(executionId));
    }

    private JobDefinition requireTriggerable(String jobName) {
        JobDefinition definition = catalog.find(jobName)
                .orElseThrow(() -> AdminException.jobNotFound(jobName));
        if (!definition.enabled()) {
            throw AdminException.jobNotTriggerable(jobName, "任务已停用");
        }
        if (!definition.supportsManualTrigger()) {
            throw AdminException.jobNotTriggerable(jobName, "该任务不支持人工触发");
        }
        return definition;
    }

    /**
     * 归一化 {@code scopeKey}。
     *
     * <p>三种情况三种处置，且都不静默：不接受该参数的任务收到值 → 400；
     * 接受但没传 → 用默认值；传了不在白名单里的值 → 400。
     * 静默忽略前者会让调用方以为自己限制了范围，而任务其实动了全部数据。
     */
    private static String resolveScopeKey(JobDefinition definition, String requested) {
        boolean provided = requested != null && !requested.isBlank();
        if (!definition.acceptsScopeKey()) {
            if (provided) {
                throw AdminException.invalidRequest(
                        "任务 " + definition.jobName() + "（" + definition.displayName()
                                + "）不接受作用范围参数");
            }
            return null;
        }
        if (!provided) {
            return definition.defaultScopeKey();
        }
        String normalized = requested.trim().toUpperCase(Locale.ROOT);
        if (!definition.allowedScopeKeys().contains(normalized)) {
            throw AdminException.invalidRequest(
                    "任务 " + definition.jobName() + " 的作用范围只能是 "
                            + definition.allowedScopeKeys() + "，收到：" + requested);
        }
        return normalized;
    }

    private JobExecutionQuery withResolvedRange(JobExecutionQuery query) {
        OffsetDateTime end = query.endedAt() == null ? OffsetDateTime.now(clock) : query.endedAt();
        OffsetDateTime start = query.startedAt() == null
                ? end.minusDays(JobExecutionQuery.DEFAULT_RANGE_DAYS)
                : query.startedAt();

        if (end.isBefore(start)) {
            throw AdminException.invalidRequest("结束时间不能早于开始时间");
        }
        if (Duration.between(start, end).toDays() > JobExecutionQuery.MAX_RANGE_DAYS) {
            throw AdminException.invalidRequest(
                    "任务执行记录的查询跨度不能超过 " + JobExecutionQuery.MAX_RANGE_DAYS + " 天");
        }
        return query.withRange(start, end);
    }

    private static String newBatchId() {
        return UUID.randomUUID().toString();
    }
}
