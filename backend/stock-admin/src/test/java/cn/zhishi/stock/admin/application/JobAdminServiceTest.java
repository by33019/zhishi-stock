package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import cn.zhishi.stock.system.job.JobExecutionStatus;
import cn.zhishi.stock.system.job.JobExecutionStore;
import cn.zhishi.stock.system.job.JobNames;
import cn.zhishi.stock.system.job.JobTriggerType;
import cn.zhishi.stock.system.job.NewJobExecution;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 任务管理用例（契约 §16.3 ADM-JOB-01~05）。
 *
 * <h2>本类钉的是"拒绝"而不是"成功"</h2>
 * 触发一个白名单任务并写一行记录，是这条链路上最不容易出错的部分；真正会出事的是
 * 那些"看起来也行、其实不该放行"的请求：停用的任务、不在白名单里的名字、
 * 对不接受作用范围的任务传作用范围、重试一条已经成功的执行。
 * 这些如果被放行，不会报错，只会让数据变得不可解释。
 *
 * <h2>异步性怎么测</h2>
 * 默认的 {@code RecordingDispatcher} **不执行**被提交的任务，因此可以断言
 * "接口返回时任务还没跑"——那正是 202 的含义。需要观察执行效果的用例
 * 再打开 {@code runInline}。
 */
class JobAdminServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final OffsetDateTime NOW =
            OffsetDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());
    private static final long OPERATOR = 9001L;
    private static final String TRACE = "trace-1";

    private final InMemoryJobExecutionStore store = new InMemoryJobExecutionStore();
    private final RecordingDispatcher dispatcher = new RecordingDispatcher();
    private final RecordingExecutor executor = new RecordingExecutor();
    private final AtomicLong ids = new AtomicLong(1);

    private final JobAdminService service = serviceWith(new JobDefinitionCatalog(
            60_000, 120_000, 600_000));

    // ---------- ADM-JOB-01 ----------

    @Test
    void definitionsExposeExactlyTheWhitelistedJobs() {
        assertThat(service.definitions())
                .extracting(JobDefinition::jobName)
                .containsExactlyInAnyOrderElementsOf(JobNames.ALL);
    }

    /** 调度描述必须与真实调度一致，否则页面会长期显示一个错误的事实。 */
    @Test
    void definitionsDescribeTheRealFixedDelay() {
        assertThat(service.definitions())
                .filteredOn(definition -> definition.jobName().equals(JobNames.NEWS_INGEST))
                .singleElement()
                .satisfies(definition ->
                        assertThat(definition.scheduleDescription()).isEqualTo("每 120 秒（fixedDelay）"));
    }

    // ---------- ADM-JOB-02 ----------

    @Test
    void triggerRejectsAJobOutsideTheWhitelist() {
        assertThatThrownBy(() -> service.trigger(
                "reflect-and-call-anything", command(null, null, null, null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.JOB_NOT_FOUND));
    }

    @Test
    void triggerRejectsADisabledJob() {
        JobAdminService disabled = serviceWith(new JobDefinitionCatalog(List.of(
                new JobDefinition(
                        JobNames.NEWS_INGEST, "已停用的资讯采集", JobNames.NEWS_INGEST_HANDLER,
                        "已停用", true, false, false, null, List.of(), null))));

        assertThatThrownBy(() -> disabled.trigger(
                JobNames.NEWS_INGEST, command(null, null, null, null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(AdminErrorCode.JOB_NOT_TRIGGERABLE);
                    assertThat(exception.getMessage()).contains("停用");
                });
    }

    @Test
    void triggerRejectsAJobThatDoesNotSupportManualTrigger() {
        JobAdminService automaticOnly = serviceWith(new JobDefinitionCatalog(List.of(
                new JobDefinition(
                        JobNames.NEWS_INGEST, "只能定时跑", JobNames.NEWS_INGEST_HANDLER,
                        "每 120 秒", false, false, true, null, List.of(), null))));

        assertThatThrownBy(() -> automaticOnly.trigger(
                JobNames.NEWS_INGEST, command(null, null, null, null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(AdminErrorCode.JOB_NOT_TRIGGERABLE);
                    assertThat(exception.getMessage()).contains("不支持人工触发");
                });
    }

    /** 返回时任务尚未执行——这就是 202 的含义；同步跑完再返回会让状态码说谎。 */
    @Test
    void triggerReturnsBeforeTheJobRuns() {
        JobExecution accepted = service.trigger(
                JobNames.MARKET_OVERVIEW_COLLECT, command(null, null, null, null), OPERATOR, TRACE);

        assertThat(accepted.status()).isEqualTo(JobExecutionStatus.RUNNING);
        assertThat(accepted.triggerType()).isEqualTo(JobTriggerType.MANUAL);
        assertThat(accepted.batchId()).isNotBlank();
        assertThat(accepted.startedAt()).isNotNull();
        assertThat(executor.triggers).isEmpty();
        assertThat(dispatcher.pending).hasSize(1);
    }

    @Test
    void triggerPassesTheOperatorScopeAndReasonToTheExecutor() {
        dispatcher.runInline = true;

        service.trigger(
                JobNames.MARKET_OVERVIEW_COLLECT,
                command("cn", 7L, 1, "手工补一次"),
                OPERATOR,
                TRACE);

        assertThat(executor.triggers).singleElement().satisfies(trigger -> {
            assertThat(trigger.jobName()).isEqualTo(JobNames.MARKET_OVERVIEW_COLLECT);
            // 大小写归一化：白名单里是 "CN"，客户端传 "cn" 应当被接受并归一
            assertThat(trigger.scopeKey()).isEqualTo("CN");
            assertThat(trigger.providerId()).isEqualTo(7L);
            assertThat(trigger.reason()).isEqualTo("手工补一次");
            assertThat(trigger.operatorId()).isEqualTo(OPERATOR);
        });
    }

    @Test
    void triggerFallsBackToTheDefaultScopeKey() {
        dispatcher.runInline = true;

        service.trigger(
                JobNames.MARKET_OVERVIEW_COLLECT, command(null, null, null, null), OPERATOR, TRACE);

        assertThat(executor.triggers).singleElement()
                .extracting(JobTrigger::scopeKey).isEqualTo("CN");
    }

    @Test
    void triggerRejectsAnUnknownScopeKey() {
        assertThatThrownBy(() -> service.trigger(
                JobNames.MARKET_OVERVIEW_COLLECT,
                command("US", null, null, null),
                OPERATOR,
                TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    /**
     * 对不接受作用范围的任务传了值是**错误**而不是忽略。
     *
     * <p>静默忽略会让调用方以为自己缩小了范围，而任务其实动了全部数据——
     * 那正是"参数被吃掉"最危险的形态。
     */
    @Test
    void triggerRejectsAScopeKeyForATaskThatDoesNotAcceptOne() {
        assertThatThrownBy(() -> service.trigger(
                JobNames.NEWS_INGEST, command("CN", null, null, null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST);
                    assertThat(exception.getMessage()).contains("不接受作用范围参数");
                });
    }

    @Test
    void triggerRejectsANonPositiveShardTotal() {
        assertThatThrownBy(() -> service.trigger(
                JobNames.NEWS_INGEST, command(null, null, 0, null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
    }

    /**
     * 提交被拒时必须把那条 RUNNING 记录收尾。
     *
     * <p>留在 RUNNING 的记录在页面上就是"正在跑"，而实际上它永远不会结束——
     * "卡住的任务"是运维第一个要查的信号，不能有假。
     */
    @Test
    void recordsADispatchRejectionAsFailedInsteadOfLeavingItRunning() {
        dispatcher.failure = new IllegalStateException("队列已满");

        assertThatThrownBy(() -> service.trigger(
                JobNames.NEWS_INGEST, command(null, null, null, null), OPERATOR, TRACE))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.rows.values()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(JobExecutionStatus.FAILED);
            assertThat(row.errorCategory()).isEqualTo("DISPATCH_REJECTED");
            assertThat(row.completedAt()).isEqualTo(NOW);
        });
    }

    // ---------- ADM-JOB-05 ----------

    @Test
    void retryKeepsTheBatchAndIncrementsTheAttempt() {
        dispatcher.runInline = true;
        executor.outcome = JobExecutionOutcome.failed("PROVIDER_TIMEOUT", "JOB_TIMEOUT", "上游超时");
        JobExecution first = service.trigger(
                JobNames.NEWS_INGEST, command(null, null, null, null), OPERATOR, TRACE);
        assertThat(first.status()).isEqualTo(JobExecutionStatus.FAILED);

        executor.outcome = JobExecutionOutcome.success();
        JobExecution retried = service.retry(first.executionId(), new RetryJobCommand("修好后重试"), OPERATOR, TRACE);

        assertThat(retried.triggerType()).isEqualTo(JobTriggerType.RETRY);
        assertThat(retried.attemptNo()).isEqualTo(2);
        assertThat(retried.batchId())
                .describedAs("重试沿用原批次，唯一索引才挡得住同批同次的重复记录")
                .isEqualTo(first.batchId());
        assertThat(retried.executionId()).isNotEqualTo(first.executionId());
        assertThat(store.rows).hasSize(2);
        assertThat(store.rows.get(first.executionId()).status())
                .describedAs("旧执行记录必须原样保留，重试不覆盖它")
                .isEqualTo(JobExecutionStatus.FAILED);
    }

    @Test
    void retryInheritsTheProviderAndShardFromThePreviousExecution() {
        dispatcher.runInline = true;
        executor.outcome = JobExecutionOutcome.partial(null, "PARTIAL", "两家来源超时");
        JobExecution first = service.trigger(
                JobNames.NEWS_INGEST, command(null, 42L, 2, null), OPERATOR, TRACE);

        executor.outcome = JobExecutionOutcome.success();
        JobExecution retried = service.retry(first.executionId(), new RetryJobCommand(null), OPERATOR, TRACE);

        assertThat(retried.providerId()).isEqualTo(42L);
        assertThat(retried.shardTotal()).isEqualTo(2);
    }

    @Test
    void retryRefusesAnExecutionThatDidNotFail() {
        dispatcher.runInline = true;
        executor.outcome = JobExecutionOutcome.success();
        JobExecution completed = service.trigger(
                JobNames.NEWS_INGEST, command(null, null, null, null), OPERATOR, TRACE);

        assertThatThrownBy(() -> service.retry(
                completed.executionId(), new RetryJobCommand(null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(AdminErrorCode.JOB_EXECUTION_NOT_RETRYABLE);
                    assertThat(exception.getMessage()).contains("SUCCESS");
                });
    }

    @Test
    void retryRefusesAnUnknownExecution() {
        assertThatThrownBy(() -> service.retry(404L, new RetryJobCommand(null), OPERATOR, TRACE))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.JOB_EXECUTION_NOT_FOUND));
    }

    // ---------- ADM-JOB-03 / 04 ----------

    @Test
    void listResolvesTheDefaultRangeAndBuildsThePageEnvelope() {
        store.total = 42;

        PageData<JobExecution> page = service.list(query(null, null, 1, 20));

        assertThat(store.lastCountQuery.startedAt()).isEqualTo(NOW.minusDays(7));
        assertThat(store.lastCountQuery.endedAt()).isEqualTo(NOW);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(42);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void listRejectsARangeWiderThanNinetyDays() {
        assertThatThrownBy(() -> service.list(
                query(NOW.minusDays(91), NOW, 1, 20)))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code()).isEqualTo(AdminErrorCode.INVALID_REQUEST));
        assertThat(service.list(query(NOW.minusDays(90), NOW, 1, 20))).isNotNull();
    }

    @Test
    void listSkipsThePageQueryWhenNothingMatches() {
        store.total = 0;

        assertThat(service.list(query(null, null, 1, 20)).items()).isEmpty();
        assertThat(store.pageCalls).isZero();
    }

    @Test
    void detailReportsAnUnknownExecution() {
        assertThatThrownBy(() -> service.detail(404L))
                .isInstanceOfSatisfying(AdminException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminErrorCode.JOB_EXECUTION_NOT_FOUND));
    }

    // ---------- 夹具 ----------

    private JobAdminService serviceWith(JobDefinitionCatalog catalog) {
        return new JobAdminService(
                catalog,
                executor,
                store,
                new JobExecutionRecorder(store, ids::incrementAndGet, CLOCK),
                dispatcher,
                CLOCK);
    }

    private static TriggerJobCommand command(
            String scopeKey, Long providerId, Integer shardTotal, String reason) {
        return new TriggerJobCommand(scopeKey, providerId, shardTotal, reason);
    }

    private static JobExecutionQuery query(
            OffsetDateTime startedAt, OffsetDateTime endedAt, int page, int size) {
        return new JobExecutionQuery(null, null, null, null, null, startedAt, endedAt, page, size);
    }

    private static final class RecordingExecutor implements JobTaskExecutor {

        private final List<JobTrigger> triggers = new ArrayList<>();
        private JobExecutionOutcome outcome = JobExecutionOutcome.success();

        @Override
        public JobExecutionOutcome execute(JobTrigger trigger) {
            triggers.add(trigger);
            return outcome;
        }
    }

    private static final class RecordingDispatcher implements JobExecutionDispatcher {

        private final List<Runnable> pending = new ArrayList<>();
        private boolean runInline;
        private RuntimeException failure;

        @Override
        public void submit(Runnable task) {
            if (failure != null) {
                throw failure;
            }
            pending.add(task);
            if (runInline) {
                task.run();
            }
        }
    }

    private static final class InMemoryJobExecutionStore implements JobExecutionStore {

        private final Map<Long, JobExecution> rows = new LinkedHashMap<>();
        private long total;
        private int pageCalls;
        private JobExecutionQuery lastCountQuery;

        @Override
        public void insert(NewJobExecution execution) {
            rows.put(execution.executionId(), running(execution));
        }

        @Override
        public boolean complete(
                long executionId, JobExecutionOutcome outcome, OffsetDateTime completedAt) {
            JobExecution previous = rows.get(executionId);
            if (previous == null || previous.status() != JobExecutionStatus.RUNNING) {
                return false;
            }
            rows.put(executionId, completed(previous, outcome, completedAt));
            return true;
        }

        @Override
        public List<JobExecution> page(JobExecutionQuery query) {
            pageCalls++;
            return new ArrayList<>(rows.values());
        }

        @Override
        public long count(JobExecutionQuery query) {
            lastCountQuery = query;
            return total;
        }

        @Override
        public Optional<JobExecution> find(long executionId) {
            return Optional.ofNullable(rows.get(executionId));
        }

        @Override
        public int nextAttemptNo(String jobName, String batchId, int shardIndex) {
            return rows.values().stream()
                            .filter(row -> row.jobName().equals(jobName)
                                    && row.batchId().equals(batchId)
                                    && row.shardIndex() == shardIndex)
                            .mapToInt(JobExecution::attemptNo)
                            .max()
                            .orElse(0)
                    + 1;
        }

        private static JobExecution running(NewJobExecution execution) {
            JobExecutionRequest request = execution.request();
            return new JobExecution(
                    execution.executionId(),
                    request.jobName(),
                    request.handlerName(),
                    request.batchId(),
                    request.providerId(),
                    request.triggerType(),
                    JobExecutionStatus.RUNNING,
                    request.shardIndex(),
                    request.shardTotal(),
                    request.attemptNo(),
                    request.scheduledAt(),
                    execution.startedAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    request.traceId(),
                    execution.startedAt(),
                    execution.startedAt());
        }

        private static JobExecution completed(
                JobExecution previous, JobExecutionOutcome outcome, OffsetDateTime completedAt) {
            return new JobExecution(
                    previous.executionId(),
                    previous.jobName(),
                    previous.handlerName(),
                    previous.batchId(),
                    previous.providerId(),
                    previous.triggerType(),
                    outcome.status(),
                    previous.shardIndex(),
                    previous.shardTotal(),
                    previous.attemptNo(),
                    previous.scheduledAt(),
                    previous.startedAt(),
                    completedAt,
                    null,
                    null,
                    outcome.counts(),
                    outcome.errorCategory(),
                    outcome.errorCode(),
                    outcome.errorSummary(),
                    previous.traceId(),
                    previous.createdAt(),
                    completedAt);
        }
    }
}
