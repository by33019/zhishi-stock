package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.domain.AiContextBuildResult;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextSnapshot;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiContextType;
import cn.zhishi.stock.ai.domain.AiDataCutoff;
import cn.zhishi.stock.ai.domain.AiFixtures;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSceneCatalog;
import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionQuery;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiSessionSummary;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskQueueMessage;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * AI-03 / AI-04 / AI-06 / AI-07 / AI-08 编排用例测试。
 *
 * <p>它守着三类**不会报错**的缺陷：
 *
 * <ol>
 *   <li><b>闸门顺序漂移</b>：并发上限、每日额度、核心行情三道闸门若顺序不定，
 *       同一份请求会在两次提交之间报不同的错，前端提示随之跳动。
 *   <li><b>幂等的第二层缺失</b>：只靠 Redis 的幂等键，一旦 Redis 抖动就会重复创建任务，
 *       而"额度被重复消耗"在页面上表现为"额度莫名少了一次"。
 *   <li><b>状态与取消意图混为一谈</b>：把已完成任务改成 {@code CANCELED}，
 *       用户会丢掉一份已经生成的报告。
 * </ol>
 *
 * <p>存储与队列用手写的内存桩而不是 Mockito：本类要断言的是**多次调用之间的状态**
 * （幂等回放、并发计数、入队次数），而 Mockito 的 {@code verify} 只能数调用次数，
 * 数不出"库里到底有几行"。
 */
class AiTaskServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final long USER = 1001L;
    private static final long OTHER_USER = 2002L;
    private static final String SECURITY_ID = "sim-600519";
    private static final long SECURITY_STORAGE_ID = 600519L;

    private final AiSceneCatalog catalog = new AiSceneCatalog();
    private final SecurityIdentityProvider securities = mock(SecurityIdentityProvider.class);
    private final SectorIdentityProvider sectors = mock(SectorIdentityProvider.class);
    private final AiContextBuilder contextBuilder = mock(AiContextBuilder.class);
    private final InMemoryTaskStore tasks = new InMemoryTaskStore();
    private final InMemorySessionStore sessions = new InMemorySessionStore();
    private final InMemoryMessageStore messages = new InMemoryMessageStore();
    private final InMemoryReportStore reports = new InMemoryReportStore();
    private final RecordingQueue queue = new RecordingQueue();

    private int dailyLimit = 20;
    private int maxConcurrent = 2;

    @BeforeEach
    void registerMasterData() {
        SecurityIdentity identity =
                new SecurityIdentity(SECURITY_STORAGE_ID, AiFixtures.security("600519", "模拟证券600519"));
        when(securities.resolve(SECURITY_ID)).thenReturn(Optional.of(identity));
        when(securities.findByStorageIds(any())).thenAnswer(invocation -> {
            Set<Long> asked = invocation.getArgument(0);
            return asked.contains(SECURITY_STORAGE_ID) ? Map.of(SECURITY_STORAGE_ID, identity) : Map.of();
        });
        when(sectors.resolve(any())).thenReturn(Optional.empty());
        when(sectors.findByStorageIds(any())).thenReturn(Map.of());
        // 默认：核心行情齐备。
        //
        // ⚠️ 本桩与上面 securities.findByStorageIds 的桩都**解引用了入参**，
        // 所以测试里若要覆盖它们，必须用 `doReturn(...).when(mock).method(...)`，
        // 不能用 `when(mock.method(any())).thenReturn(...)` —— 后者会先以 null 调用一次
        // 已有桩的 answer，于是 NPE 发生在"打桩那一行"，看起来像被测代码的错。
        when(contextBuilder.build(any(), any(), any())).thenAnswer(invocation ->
                availableResult(invocation.getArgument(0)));
    }

    private AiTaskService service() {
        return new AiTaskService(
                new AiTaskRequestResolver(catalog, securities, sectors),
                contextBuilder,
                tasks,
                sessions,
                messages,
                reports,
                queue,
                new AiTargetHydrator(securities, sectors),
                new AtomicLong(10_000L)::incrementAndGet,
                CLOCK,
                // 配额守卫与 USER-07 走同一个服务：这里装真的那个，而不是再插一份桩，
                // 否则"额度怎么算"在测试里与实际运行时会分叉。
                new AiQuotaQueryService(tasks, CLOCK, dailyLimit, maxConcurrent),
                Duration.ofSeconds(60),
                "SIMULATED",
                "sim-analyst-v1");
    }

    private static AiContextBuildResult availableResult(List<AiContextTarget> targets) {
        List<AiContextSnapshot> snapshots = new ArrayList<>();
        int no = 1;
        for (AiContextTarget target : targets) {
            snapshots.add(new AiContextSnapshot(
                    no++,
                    AiContextType.QUOTE,
                    "SECURITY",
                    target.storageId(),
                    "security:" + target.targetCode(),
                    AiFixtures.DATA_TIME,
                    AiFixtures.DATA_TIME,
                    "a".repeat(64),
                    Map.of("securityId", target.targetCode()),
                    true));
        }
        return new AiContextBuildResult(snapshots, List.of(), List.of(), true);
    }

    private static AiTaskCreationRequest stockRequest(String sessionId, String question) {
        return new AiTaskCreationRequest(
                sessionId,
                "STOCK",
                List.of(new AiTargetRequest("SECURITY", SECURITY_ID, "PRIMARY")),
                null,
                null,
                question);
    }

    // ---------- 创建 ----------

    @Test
    @DisplayName("创建：受理后状态为 QUEUED，返回三条链接信息与配额")
    void createAcceptsAndQueues() {
        AiTaskAccepted accepted = service().create(stockRequest(null, "近期量价如何"), USER, "key-1", "trace-1");

        assertThat(accepted.task().status()).isEqualTo(AiTaskStatus.QUEUED);
        assertThat(accepted.task().progressStage()).isEqualTo("排队中");
        assertThat(accepted.statusUrl()).isEqualTo("/api/v1/ai/tasks/" + accepted.task().taskId());
        assertThat(accepted.streamUrl()).isEqualTo(accepted.statusUrl() + "/stream");
        assertThat(accepted.quota().usedCount()).isEqualTo(1);
        assertThat(accepted.quota().remainingCount()).isEqualTo(dailyLimit - 1);
        assertThat(queue.enqueued).containsExactly(Long.parseLong(accepted.task().taskId()));
        assertThat(messages.rows).hasSize(1);
        assertThat(messages.rows.get(0).roleType()).isEqualTo(AiMessageRole.USER);
        assertThat(messages.rows.get(0).sequenceNo()).isEqualTo(1);
    }

    /**
     * 投递必须发生在**事务提交之后**。
     *
     * <p>在事务里 XADD，worker 可能抢在提交前读到消息，而那时 {@code ai_task} 里
     * 还没有这一行——它会记一条「任务不存在」并 ACK 掉消息，事务随后提交，
     * 于是库里留下一个永远不会被执行的 {@code QUEUED} 任务，要等恢复扫描
     * （默认 5 分钟）才被重新投出去。
     *
     * <p>这条不变量**只有**用真实的 {@code TransactionSynchronizationManager} 才验得到：
     * 本类的其它用例没有事务，{@code enqueueAfterCommit} 会走"立即投递"那条分支，
     * 于是"投递发生在提交后"与"投递发生在提交前"在它们眼里完全一样。
     * 真实缺陷由 M3-07 的端到端脚本复现（6 个任务里 1 个卡住），这里是它的确定性版本。
     */
    @Test
    @DisplayName("创建：投递发生在事务提交之后（提交前队列必须是空的）")
    void enqueuesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            AiTaskAccepted accepted =
                    service().create(stockRequest(null, "近期量价如何"), USER, "key-1", "trace-1");

            assertThat(queue.enqueued)
                    .describedAs("事务还没提交，此刻投递会让 worker 读到一个不存在的任务")
                    .isEmpty();

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            assertThat(queue.enqueued)
                    .containsExactly(Long.parseLong(accepted.task().taskId()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("创建：未传 sessionId 时新建会话，且会话的场景与标题来自请求")
    void createCreatesSessionWhenAbsent() {
        AiTaskAccepted accepted = service().create(stockRequest(null, "近期量价如何"), USER, "key-1", "t");

        long sessionId = Long.parseLong(accepted.task().sessionId());
        AiSession session = sessions.byId.get(sessionId);
        assertThat(session).isNotNull();
        assertThat(session.userId()).isEqualTo(USER);
        assertThat(session.scene()).isEqualTo(AiScene.STOCK);
        assertThat(session.status()).isEqualTo("ACTIVE");
        assertThat(session.lastTaskId()).isEqualTo(Long.parseLong(accepted.task().taskId()));
        assertThat(session.title()).isEqualTo("近期量价如何");
    }

    @Test
    @DisplayName("创建：未传 sessionId 且没有问题摘要时，标题取场景名与首个目标名")
    void createDerivesTitleFromSceneAndTarget() {
        AiTaskAccepted accepted = service().create(stockRequest(null, null), USER, "key-1", "t");
        AiSession session = sessions.byId.get(Long.parseLong(accepted.task().sessionId()));
        assertThat(session.title()).isEqualTo("个股研究 · 模拟证券600519");
    }

    @Test
    @DisplayName("创建：目标在响应里带对外标识（不是库里的 bigint）")
    void createReturnsExternalTargetIds() {
        AiTaskAccepted accepted = service().create(stockRequest(null, null), USER, "key-1", "t");
        assertThat(accepted.task().targets()).hasSize(1);
        assertThat(accepted.task().targets().get(0).targetId()).isEqualTo(SECURITY_ID);
        assertThat(accepted.task().targets().get(0).targetCode()).isEqualTo("600519");
    }

    // ---------- 幂等 ----------

    @Test
    @DisplayName("幂等：同一 Idempotency-Key 重复提交返回同一个任务，库里只有一行")
    void createIsIdempotentByRequestId() {
        AiTaskService service = service();
        AiTaskAccepted first = service.create(stockRequest(null, "q"), USER, "same-key", "t1");
        AiTaskAccepted second = service.create(stockRequest(null, "q"), USER, "same-key", "t2");

        assertThat(second.task().taskId()).isEqualTo(first.task().taskId());
        assertThat(tasks.rows).hasSize(1);
        // 回放不再写第二条 USER 消息，也不再入队第二次
        assertThat(messages.rows).hasSize(1);
        assertThat(queue.enqueued).hasSize(1);
    }

    @Test
    @DisplayName("幂等：request_id 由 (userId, key) 确定性派生，不同用户同 key 得到不同值")
    void requestIdIsDerivedDeterministically() {
        String a = AiTaskService.requestIdOf(USER, "same-key");
        String b = AiTaskService.requestIdOf(USER, "same-key");
        String other = AiTaskService.requestIdOf(OTHER_USER, "same-key");

        assertThat(a).isEqualTo(b).hasSize(36);
        assertThat(other).isNotEqualTo(a);
    }

    @Test
    @DisplayName("幂等：两个用户传同一个 key 都能创建成功（request_id 不同，不撞全局唯一索引）")
    void sameKeyFromDifferentUsersBothSucceed() {
        AiTaskService service = service();
        AiTaskAccepted mine = service.create(stockRequest(null, "q"), USER, "shared", "t");
        AiTaskAccepted theirs = service.create(stockRequest(null, "q"), OTHER_USER, "shared", "t");

        assertThat(theirs.task().taskId()).isNotEqualTo(mine.task().taskId());
        assertThat(tasks.rows).hasSize(2);
    }

    // ---------- 闸门 ----------

    @Test
    @DisplayName("并发上限：已有 2 个活跃任务时第 3 个返回 429")
    void concurrencyLimitRejectsThird() {
        AiTaskService service = service();
        service.create(stockRequest(null, "q1"), USER, "k1", "t");
        service.create(stockRequest(null, "q2"), USER, "k2", "t");

        assertThatThrownBy(() -> service.create(stockRequest(null, "q3"), USER, "k3", "t"))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.CONCURRENCY_EXCEEDED))
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("并发上限：把其中一个置终态后可以再创建")
    void concurrencyLimitReleasesOnTerminal() {
        AiTaskService service = service();
        AiTaskAccepted first = service.create(stockRequest(null, "q1"), USER, "k1", "t");
        service.create(stockRequest(null, "q2"), USER, "k2", "t");
        tasks.forceStatus(Long.parseLong(first.task().taskId()), AiTaskStatus.COMPLETED);

        AiTaskAccepted third = service.create(stockRequest(null, "q3"), USER, "k3", "t");
        assertThat(third.task().status()).isEqualTo(AiTaskStatus.QUEUED);
    }

    @Test
    @DisplayName("每日额度：用完后返回 AI_QUOTA_EXCEEDED，且响应体带 dailyLimit 与 usedCount")
    void quotaLimitRejectsAndCarriesQuota() {
        dailyLimit = 2;
        // 并发上限必须放宽，否则第 3 次会先撞并发（闸门顺序：并发 → 额度），
        // 那样测的就不是额度了。这条用例要单独钉住"额度闸门本身"。
        maxConcurrent = 10;
        AiTaskService service = service();
        service.create(stockRequest(null, "q1"), USER, "k1", "t");
        service.create(stockRequest(null, "q2"), USER, "k2", "t");

        assertThatThrownBy(() -> service.create(stockRequest(null, "q3"), USER, "k3", "t"))
                .isInstanceOf(AiQuotaExceededException.class)
                .satisfies(exception -> {
                    AiTaskQuota quota = ((AiQuotaExceededException) exception).quota();
                    assertThat(quota.dailyLimit()).isEqualTo(2);
                    assertThat(quota.usedCount()).isEqualTo(2);
                    assertThat(quota.remainingCount()).isZero();
                });
    }

    @Test
    @DisplayName("每日额度：额度按 Asia/Shanghai 自然日统计，昨日的任务不计入")
    void quotaCountsOnlyToday() {
        AiTaskService service = service();
        service.create(stockRequest(null, "q1"), USER, "k1", "t");
        // 把唯一那行挪到昨天（模拟昨日任务）
        tasks.shiftCreatedAt(tasks.rows.get(0).taskId(), -1);

        AiTaskQuota quota = service.quotaOf(USER);
        assertThat(quota.usedCount()).isZero();
    }

    @Test
    @DisplayName("闸门顺序：并发先于额度（两者都满时报并发）")
    void concurrencyGatePrecedesQuotaGate() {
        dailyLimit = 2;
        AiTaskService service = service();
        service.create(stockRequest(null, "q1"), USER, "k1", "t");
        service.create(stockRequest(null, "q2"), USER, "k2", "t");

        // 此刻并发与额度都刚好用满：spec §3.4 的顺序是 并发(3) → 额度(4)，
        // 所以必须报并发。顺序若被对调，这条会红——而两种错都是 429，
        // 只在业务码上分叉，页面上表现为"额度提示与实际不符"。
        assertThatThrownBy(() -> service.create(stockRequest(null, "q3"), USER, "k3", "t"))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.CONCURRENCY_EXCEEDED));
    }

    @Test
    @DisplayName("核心行情缺失：拒绝创建并返回 AI_CORE_DATA_MISSING（503 语义），不落库不入队")
    void missingCoreDataRejectsCreation() {
        doReturn(new AiContextBuildResult(List.of(), List.of(), List.of("行情快照暂不可用"), false))
                .when(contextBuilder)
                .build(any(), any(), any());

        assertThatThrownBy(() -> service().create(stockRequest(null, "q"), USER, "k", "t"))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.CORE_DATA_MISSING))
                .hasMessageContaining("行情快照暂不可用");
        assertThat(tasks.rows).isEmpty();
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    @DisplayName("闸门顺序：目标非法先于并发与额度报错（同一份请求稳定报同一条错）")
    void invalidTargetReportedBeforeGates() {
        AiTaskService service = service();
        // 先占满并发，再提交一份目标也非法的请求
        service.create(stockRequest(null, "q1"), USER, "k1", "t");
        service.create(stockRequest(null, "q2"), USER, "k2", "t");

        AiTaskCreationRequest bad = new AiTaskCreationRequest(
                null,
                "STOCK",
                List.of(new AiTargetRequest("SECURITY", "sim-999999", "PRIMARY")),
                null,
                null,
                null);
        assertThatThrownBy(() -> service.create(bad, USER, "k3", "t"))
                .isInstanceOf(InvalidAiTargetException.class);
    }

    // ---------- 校验口径一致 ----------

    @Test
    @DisplayName("一致性：同一份非法请求，预览入口与创建入口报同一个业务码与同一条消息")
    void previewAndCreateShareValidation() {
        AiTaskService service = service();
        AiContextPreviewService previews = new AiContextPreviewService(
                new AiTaskRequestResolver(catalog, securities, sectors), contextBuilder);

        List<AiTargetRequest> badTargets = List.of(
                new AiTargetRequest("SECURITY", SECURITY_ID, "PRIMARY"),
                new AiTargetRequest("SECURITY", SECURITY_ID, "COMPARISON"));
        AiContextPreviewRequest previewRequest = new AiContextPreviewRequest(
                "COMPARE", badTargets, null, null);
        AiTaskCreationRequest createRequest = new AiTaskCreationRequest(
                null, "COMPARE", badTargets, null, null, null);

        Throwable fromPreview = catchThrowable(() -> previews.preview(previewRequest));
        Throwable fromCreate = catchThrowable(() -> service.create(createRequest, USER, "k", "t"));

        assertThat(fromPreview).isInstanceOf(InvalidAiTargetException.class);
        assertThat(fromCreate).isInstanceOf(InvalidAiTargetException.class);
        assertThat(((InvalidAiTargetException) fromCreate).code())
                .isEqualTo(((InvalidAiTargetException) fromPreview).code());
        assertThat(fromCreate.getMessage()).isEqualTo(fromPreview.getMessage());
    }

    @Test
    @DisplayName("一致性：场景未知时两个入口都报 INVALID_REQUEST 且消息相同")
    void previewAndCreateShareUnknownSceneError() {
        AiTaskService service = service();
        AiContextPreviewService previews = new AiContextPreviewService(
                new AiTaskRequestResolver(catalog, securities, sectors), contextBuilder);

        Throwable fromPreview = catchThrowable(() -> previews.preview(new AiContextPreviewRequest(
                "NOPE", List.of(), null, null)));
        Throwable fromCreate = catchThrowable(() -> service.create(
                new AiTaskCreationRequest(null, "NOPE", List.of(), null, null, null), USER, "k", "t"));

        assertThat(((InvalidAiContextQueryException) fromPreview).code()).isEqualTo("INVALID_REQUEST");
        assertThat(fromCreate.getMessage()).isEqualTo(fromPreview.getMessage());
    }

    @Test
    @DisplayName("问题长度：超过场景上限被拒（上限取自场景定义而不是写死 500）")
    void overlongQuestionRejected() {
        String tooLong = "问".repeat(501);
        assertThatThrownBy(() -> service().create(stockRequest(null, tooLong), USER, "k", "t"))
                .isInstanceOf(InvalidAiContextQueryException.class)
                .hasMessageContaining("500");
    }

    // ---------- AI-04 查询 ----------

    @Test
    @DisplayName("查询：本人的任务可以查到，他人的任务一律 404 语义（不泄露存在性）")
    void getHidesOtherUsersTasks() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k", "t");
        long taskId = Long.parseLong(accepted.task().taskId());

        assertThat(service.get(taskId, USER).taskId()).isEqualTo(accepted.task().taskId());
        assertThatThrownBy(() -> service.get(taskId, OTHER_USER))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.TASK_NOT_FOUND));
    }

    @Test
    @DisplayName("查询：从库里读回的目标经身份端口还原出对外标识")
    void getHydratesTargetIds() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k", "t");
        long taskId = Long.parseLong(accepted.task().taskId());

        AiTaskSummary summary = service.get(taskId, USER);
        assertThat(summary.targets().get(0).targetId()).isEqualTo(SECURITY_ID);
        assertThat(summary.targets().get(0).storageId()).isEqualTo(SECURITY_STORAGE_ID);
    }

    @Test
    @DisplayName("查询：主数据查不到时保留目标并降级为无对外标识，不失败")
    void getDegradesWhenIdentityMissing() {
        doReturn(Map.of()).when(securities).findByStorageIds(any());
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k", "t");
        long taskId = Long.parseLong(accepted.task().taskId());

        AiTaskSummary summary = service.get(taskId, USER);
        assertThat(summary.targets()).hasSize(1);
        assertThat(summary.targets().get(0).targetId()).isNull();
        assertThat(summary.targets().get(0).targetCode()).isEqualTo("600519");
    }

    // ---------- AI-06 取消 ----------

    @Test
    @DisplayName("取消：活跃任务置取消意图并立即生效")
    void cancelActiveTaskRecordsIntent() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k", "t");
        long taskId = Long.parseLong(accepted.task().taskId());

        AiCancelResult result = service.cancel(taskId, USER);
        assertThat(result.cancelRequested()).isTrue();
        assertThat(result.effectiveImmediately()).isTrue();
        assertThat(result.status()).isEqualTo(AiTaskStatus.QUEUED);
        assertThat(tasks.byId.get(taskId).cancelRequested()).isTrue();
    }

    @Test
    @DisplayName("取消：已完成的任务保持完成，只有 effectiveImmediately=false")
    void cancelCompletedTaskKeepsStatus() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k", "t");
        long taskId = Long.parseLong(accepted.task().taskId());
        tasks.forceStatus(taskId, AiTaskStatus.COMPLETED);

        AiCancelResult result = service.cancel(taskId, USER);
        assertThat(result.status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(result.effectiveImmediately()).isFalse();
        assertThat(result.cancelRequested()).isFalse();
        assertThat(tasks.byId.get(taskId).status()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(tasks.byId.get(taskId).cancelRequested()).isFalse();
    }

    @Test
    @DisplayName("取消：他人的任务 404 语义")
    void cancelHidesOtherUsersTasks() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k", "t");
        long taskId = Long.parseLong(accepted.task().taskId());

        assertThatThrownBy(() -> service.cancel(taskId, OTHER_USER))
                .isInstanceOf(AiTaskException.class);
    }

    // ---------- AI-07 重试 ----------

    @Test
    @DisplayName("重试：失败任务创建新任务并置 retryOfTaskId，原任务与旧用量不变")
    void retryCreatesNewTaskAndKeepsOriginal() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "原问题"), USER, "k1", "t");
        long originalId = Long.parseLong(accepted.task().taskId());
        tasks.forceStatus(originalId, AiTaskStatus.FAILED);

        AiTaskAccepted retried = service.retry(originalId, USER, "k2", null, "t");

        assertThat(retried.task().taskId()).isNotEqualTo(accepted.task().taskId());
        long newId = Long.parseLong(retried.task().taskId());
        assertThat(tasks.byId.get(newId).retryOfTaskId()).isEqualTo(originalId);
        assertThat(tasks.byId.get(originalId).status()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(retried.task().reportId())
                .describedAs("原任务没有报告，所以它的 reportId 为空")
                .isNull();
        // 旧用量不被覆盖：额度按任务数计，两个任务算两次
        assertThat(retried.quota().usedCount()).isEqualTo(2);
        // 复用原任务的会话与目标
        assertThat(retried.task().sessionId()).isEqualTo(accepted.task().sessionId());
        assertThat(retried.task().targets().get(0).targetId()).isEqualTo(SECURITY_ID);
    }

    @Test
    @DisplayName("重试：未提供 question 时沿用原问题")
    void retryKeepsOriginalQuestion() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "原问题"), USER, "k1", "t");
        long originalId = Long.parseLong(accepted.task().taskId());
        tasks.forceStatus(originalId, AiTaskStatus.TIMED_OUT);

        AiTaskAccepted retried = service.retry(originalId, USER, "k2", null, "t");
        assertThat(tasks.byId.get(Long.parseLong(retried.task().taskId())).question())
                .isEqualTo("原问题");
    }

    @Test
    @DisplayName("重试：非失败状态返回 AI_TASK_NOT_RETRYABLE（含已取消——不该被复活）")
    void retryRejectsNonRetryableStatuses() {
        AiTaskService service = service();
        AiTaskAccepted accepted = service.create(stockRequest(null, "q"), USER, "k1", "t");
        long taskId = Long.parseLong(accepted.task().taskId());

        for (AiTaskStatus status : List.of(
                AiTaskStatus.QUEUED, AiTaskStatus.CANCELED, AiTaskStatus.COMPLETED)) {
            tasks.forceStatus(taskId, status);
            assertThatThrownBy(() -> service.retry(taskId, USER, "k2", null, "t"))
                    .as("状态 %s 不应可重试", status)
                    .isInstanceOf(AiTaskException.class)
                    .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                            .isEqualTo(AiTaskErrorCode.TASK_NOT_RETRYABLE));
        }
    }

    // ---------- AI-08 追问 ----------

    @Test
    @DisplayName("追问：在活动会话里创建新任务，复用会话场景与最近任务的目标")
    void followUpCreatesTaskInSession() {
        AiTaskService service = service();
        AiTaskAccepted first = service.create(stockRequest(null, "首问"), USER, "k1", "t");
        long sessionId = Long.parseLong(first.task().sessionId());

        AiTaskAccepted followUp = service.followUp(
                sessionId, USER, new AiFollowUpRequest("再问", null, null), "k2", "t");

        assertThat(followUp.task().sessionId()).isEqualTo(first.task().sessionId());
        assertThat(followUp.task().taskId()).isNotEqualTo(first.task().taskId());
        assertThat(followUp.task().scene()).isEqualTo(AiScene.STOCK);
        assertThat(followUp.task().targets().get(0).targetId()).isEqualTo(SECURITY_ID);
        // 重新固化上下文：又取了一次数
        assertThat(tasks.rows).hasSize(2);
    }

    @Test
    @DisplayName("追问：必须提供 question")
    void followUpRequiresQuestion() {
        AiTaskService service = service();
        AiTaskAccepted first = service.create(stockRequest(null, "首问"), USER, "k1", "t");
        long sessionId = Long.parseLong(first.task().sessionId());

        assertThatThrownBy(() -> service.followUp(
                sessionId, USER, new AiFollowUpRequest("  ", null, null), "k2", "t"))
                .isInstanceOf(InvalidAiContextQueryException.class)
                .hasMessageContaining("question");
    }

    @Test
    @DisplayName("追问：他人的会话与不存在的会话同报 SESSION_NOT_FOUND")
    void followUpHidesOtherUsersSessions() {
        AiTaskService service = service();
        AiTaskAccepted first = service.create(stockRequest(null, "首问"), USER, "k1", "t");
        long sessionId = Long.parseLong(first.task().sessionId());

        assertThatThrownBy(() -> service.followUp(
                sessionId, OTHER_USER, new AiFollowUpRequest("再问", null, null), "k2", "t"))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.SESSION_NOT_FOUND));
        assertThatThrownBy(() -> service.followUp(
                999_999L, USER, new AiFollowUpRequest("再问", null, null), "k3", "t"))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("追问：非活动会话返回 AI_SESSION_READ_ONLY")
    void followUpRejectsInactiveSession() {
        AiTaskService service = service();
        AiTaskAccepted first = service.create(stockRequest(null, "首问"), USER, "k1", "t");
        long sessionId = Long.parseLong(first.task().sessionId());
        sessions.forceStatus(sessionId, "READ_ONLY");

        assertThatThrownBy(() -> service.followUp(
                sessionId, USER, new AiFollowUpRequest("再问", null, null), "k2", "t"))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.SESSION_READ_ONLY));
    }

    @Test
    @DisplayName("追问：会话没有任务时拒绝（不凭空造一个分析对象）")
    void followUpRejectsSessionWithoutTask() {
        sessions.insert(new AiSession(777L, USER, AiScene.STOCK, "空会话", "ACTIVE",
                false, null, OffsetDateTime.now(CLOCK), 0, OffsetDateTime.now(CLOCK)));

        assertThatThrownBy(() -> service().followUp(
                777L, USER, new AiFollowUpRequest("再问", null, null), "k", "t"))
                .isInstanceOf(InvalidAiContextQueryException.class)
                .hasMessageContaining("没有可追问的任务");
    }

    // ---------- 配额形状 ----------

    @Test
    @DisplayName("配额：remainingCount 不会为负，resetsAt 是次日零点（Asia/Shanghai）")
    void quotaShapeIsSane() {
        AiTaskQuota quota = service().quotaOf(USER);
        assertThat(quota.remainingCount()).isEqualTo(dailyLimit);
        assertThat(quota.resetsAt().toString()).isEqualTo("2026-09-21T00:00+08:00");
        assertThat(quota.concurrentLimit()).isEqualTo(maxConcurrent);
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    // ---------- 内存桩 ----------

    /** 内存任务存储。乐观锁用 {@code version} 字段如实模拟，条件更新返回"影响几行"。 */
    private static final class InMemoryTaskStore implements AiTaskStore {

        private final Map<Long, AiTask> byId = new LinkedHashMap<>();
        private final Map<Long, List<AiContextTarget>> targets = new LinkedHashMap<>();
        private final List<AiTask> rows = new ArrayList<>();

        @Override
        public Optional<AiTask> find(long taskId) {
            return Optional.ofNullable(byId.get(taskId)).map(task ->
                    new AiTask(
                            task.taskId(), task.requestId(), task.sessionId(), task.userId(),
                            task.retryOfTaskId(), task.scene(), task.question(),
                            task.analysisStartAt(), task.analysisEndAt(), task.status(),
                            task.cancelRequested(), task.attemptNo(), task.maxAttempts(),
                            task.providerCode(), task.modelCode(), task.traceId(), task.createdAt(),
                            task.queuedAt(), task.startedAt(), task.firstChunkAt(),
                            task.validatingAt(), task.heartbeatAt(), task.deadlineAt(),
                            task.completedAt(), task.errorCategory(), task.errorCode(),
                            task.errorMessage(), task.version(),
                            // 模拟真实存储：库里没有对外标识，只有代理键与代码快照
                            targets.get(task.taskId()).stream()
                                    .map(target -> new AiContextTarget(
                                            target.targetType(), null, target.targetCode(),
                                            target.targetName(), target.targetRole(),
                                            target.storageId()))
                                    .toList()));
        }

        @Override
        public Optional<AiTask> findByRequestId(String requestId) {
            return byId.values().stream()
                    .filter(task -> task.requestId().equals(requestId))
                    .findFirst()
                    .flatMap(task -> find(task.taskId()));
        }

        @Override
        public void insert(AiTask task) {
            if (findByRequestIdRaw(task.requestId()) != null) {
                throw new org.springframework.dao.DuplicateKeyException("uk_ai_task_request_id");
            }
            byId.put(task.taskId(), task);
            targets.put(task.taskId(), List.copyOf(task.targets()));
            rows.add(task);
        }

        private AiTask findByRequestIdRaw(String requestId) {
            return byId.values().stream()
                    .filter(task -> task.requestId().equals(requestId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public Optional<AiTask> save(AiTask task) {
            AiTask current = byId.get(task.taskId());
            if (current == null || current.version() != task.version()) {
                return Optional.empty();
            }
            // 数据库负责推进版本，并把新版本交回给调用方（与 MyBatisAiTaskStore 同形）
            AiTask stored = task.withVersion(task.version() + 1);
            byId.put(task.taskId(), stored);
            return Optional.of(stored);
        }

        @Override
        public boolean claimForExecution(long taskId, OffsetDateTime at) {
            AiTask current = byId.get(taskId);
            if (current == null || current.cancelRequested()) {
                return false;
            }
            if (current.status() != AiTaskStatus.CREATED && current.status() != AiTaskStatus.QUEUED) {
                return false;
            }
            byId.put(taskId, withStatus(current, AiTaskStatus.PREPARING, current.attemptNo() + 1));
            return true;
        }

        @Override
        public boolean requestCancel(long taskId) {
            AiTask current = byId.get(taskId);
            if (current == null || current.cancelRequested()) {
                return false;
            }
            byId.put(taskId, new AiTask(
                    current.taskId(), current.requestId(), current.sessionId(), current.userId(),
                    current.retryOfTaskId(), current.scene(), current.question(),
                    current.analysisStartAt(), current.analysisEndAt(), current.status(), true,
                    current.attemptNo(), current.maxAttempts(), current.providerCode(),
                    current.modelCode(), current.traceId(), current.createdAt(), current.queuedAt(),
                    current.startedAt(), current.firstChunkAt(), current.validatingAt(),
                    current.heartbeatAt(), current.deadlineAt(), current.completedAt(),
                    current.errorCategory(), current.errorCode(), current.errorMessage(),
                    current.version() + 1, current.targets()));
            return true;
        }

        @Override
        public List<AiTask> findByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
            return rows.stream()
                    .map(task -> byId.get(task.taskId()))
                    .filter(task -> task.userId() == userId && statuses.contains(task.status()))
                    .sorted(Comparator.comparing(AiTask::createdAt).reversed())
                    .toList();
        }

        @Override
        public int countByUserAndStatuses(long userId, List<AiTaskStatus> statuses) {
            return (int) byId.values().stream()
                    .filter(task -> task.userId() == userId && statuses.contains(task.status()))
                    .count();
        }

        @Override
        public int countCreatedSince(long userId, OffsetDateTime from) {
            return (int) byId.values().stream()
                    .filter(task -> task.userId() == userId && !task.createdAt().isBefore(from))
                    .count();
        }

        @Override
        public List<AiTask> findRecoverable(
                OffsetDateTime queuedBefore, OffsetDateTime heartbeatBefore, int limit) {
            return List.of();
        }

        void forceStatus(long taskId, AiTaskStatus status) {
            byId.put(taskId, withStatus(byId.get(taskId), status, byId.get(taskId).attemptNo()));
        }

        void shiftCreatedAt(long taskId, int days) {
            AiTask task = byId.get(taskId);
            byId.put(taskId, new AiTask(
                    task.taskId(), task.requestId(), task.sessionId(), task.userId(),
                    task.retryOfTaskId(), task.scene(), task.question(), task.analysisStartAt(),
                    task.analysisEndAt(), task.status(), task.cancelRequested(), task.attemptNo(),
                    task.maxAttempts(), task.providerCode(), task.modelCode(), task.traceId(),
                    task.createdAt().plusDays(days), task.queuedAt(), task.startedAt(),
                    task.firstChunkAt(), task.validatingAt(), task.heartbeatAt(), task.deadlineAt(),
                    task.completedAt(), task.errorCategory(), task.errorCode(), task.errorMessage(),
                    task.version(), task.targets()));
        }

        private static AiTask withStatus(AiTask task, AiTaskStatus status, int attemptNo) {
            return new AiTask(
                    task.taskId(), task.requestId(), task.sessionId(), task.userId(),
                    task.retryOfTaskId(), task.scene(), task.question(), task.analysisStartAt(),
                    task.analysisEndAt(), status, task.cancelRequested(), attemptNo,
                    task.maxAttempts(), task.providerCode(), task.modelCode(), task.traceId(),
                    task.createdAt(), task.queuedAt(), task.startedAt(), task.firstChunkAt(),
                    task.validatingAt(), task.heartbeatAt(), task.deadlineAt(), task.completedAt(),
                    task.errorCategory(), task.errorCode(), task.errorMessage(),
                    task.version() + 1, task.targets());
        }
    }

    private static final class InMemorySessionStore implements AiSessionStore {

        private final Map<Long, AiSession> byId = new LinkedHashMap<>();

        @Override
        public Optional<AiSession> find(long sessionId) {
            return Optional.ofNullable(byId.get(sessionId));
        }

        @Override
        public void insert(AiSession session) {
            byId.put(session.sessionId(), session);
        }

        @Override
        public void touch(long sessionId, long lastTaskId, OffsetDateTime at) {
            AiSession session = byId.get(sessionId);
            byId.put(sessionId, new AiSession(
                    session.sessionId(), session.userId(), session.scene(), session.title(),
                    session.status(), session.favorite(), lastTaskId, at,
                    session.version() + 1, session.createdAt()));
        }

        void forceStatus(long sessionId, String status) {            AiSession session = byId.get(sessionId);
            byId.put(sessionId, new AiSession(
                    session.sessionId(), session.userId(), session.scene(), session.title(),
                    status, session.favorite(), session.lastTaskId(), session.lastActivityAt(),
                    session.version() + 1, session.createdAt()));
        }

        /*
         * HIS-01 的列表能力本类用不到。刻意**抛异常**而不是返回空列表：
         * 将来若有人在这里的用例里误调到它，会立刻失败；返回空列表则可能让
         * 一个本该失败的断言"因为列表为空"而通过。
         */
        @Override
        public List<AiSessionSummary> listByUser(
                long userId, AiSessionQuery query, int offset, int limit) {
            throw new UnsupportedOperationException("本测试不覆盖会话历史列表");
        }

        @Override
        public int countByUser(long userId, AiSessionQuery query) {
            throw new UnsupportedOperationException("本测试不覆盖会话历史列表");
        }

        /*
         * HIS-03 / HIS-04 的写入路径本类用不到，理由同上：抛异常而不是返回 false，
         * 否则"版本冲突"与"这个方法根本没实现"会给出同一个返回值。
         */
        @Override
        public boolean update(long sessionId, int version, String title, boolean favorite) {
            throw new UnsupportedOperationException("本测试不覆盖会话改名与收藏");
        }

        @Override
        public boolean softDelete(
                long sessionId, int version, OffsetDateTime deletedAt, OffsetDateTime purgeAfter) {
            throw new UnsupportedOperationException("本测试不覆盖会话软删");
        }
    }

    private static final class InMemoryMessageStore implements AiMessageStore {

        private final List<AiMessage> rows = new ArrayList<>();

        @Override
        public int nextSequenceNo(long sessionId) {
            return (int) rows.stream().filter(row -> row.sessionId() == sessionId).count() + 1;
        }

        @Override
        public void insert(AiMessage message) {
            rows.add(message);
        }

        @Override
        public int countBySession(long sessionId) {
            return (int) rows.stream().filter(row -> row.sessionId() == sessionId).count();
        }

        /* HIS-05 的可见消息查询本类用不到，见上面 InMemorySessionStore 的说明。 */
        @Override
        public List<AiMessage> listVisible(long sessionId, int offset, int limit) {
            throw new UnsupportedOperationException("本测试不覆盖会话消息查询");
        }

        @Override
        public int countVisible(long sessionId) {
            throw new UnsupportedOperationException("本测试不覆盖会话消息查询");
        }
    }

    /**
     * 内存报告存储。
     *
     * <p>本类**必须**有一个报告存储桩：任务的 {@code reportId} 不在任务表里，
     * 而是由 {@code AiReportStore.findByTask(taskId)} 解析出来的。
     * 没有它，"任务摘要里的 reportId"这条链路就完全没被验证过。
     */
    private static final class InMemoryReportStore implements AiReportStore {

        private final List<AiReport> rows = new ArrayList<>();

        @Override
        public void insert(AiReport report) {
            rows.add(report);
        }

        @Override
        public Optional<AiReport> find(long reportId) {
            return rows.stream().filter(row -> row.reportId() == reportId).findFirst();
        }

        @Override
        public Optional<AiReport> findByTask(long taskId) {
            return rows.stream().filter(row -> row.taskId() == taskId).findFirst();
        }
    }

    private static final class RecordingQueue implements AiTaskQueue {

        private final List<Long> enqueued = new ArrayList<>();

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
}
