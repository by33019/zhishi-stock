package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.AiFixtures;
import cn.zhishi.stock.ai.domain.AiContextBuildResult;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextSnapshot;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiContextType;
import cn.zhishi.stock.ai.domain.AiEvidenceCandidate;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportQuality;
import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEvent;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.ai.domain.LlmChunk;
import cn.zhishi.stock.ai.domain.LlmCompletion;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import cn.zhishi.stock.ai.domain.LlmEvidence;
import cn.zhishi.stock.ai.domain.LlmProviderException;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.domain.LlmRequest;
import cn.zhishi.stock.ai.domain.LlmUsage;
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
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * AI 任务执行器测试（M3-07 的核心）。
 *
 * <p>它守着四类**不会报错**的缺陷：
 *
 * <ol>
 *   <li><b>重复计费</b>：没抢到执行权仍然调了 LLM——"至少一次消费"变成"至少一次计费"。
 *   <li><b>跳过校验</b>：崩溃重启后直接 <code>RUNNING → COMPLETED</code>，
 *       一份带越界引用的文本被标成成功报告。
 *   <li><b>假成功</b>：报告落了库但任务没标 <code>COMPLETED</code>，前端永远等不到结论。
 *   <li><b>片段当结论</b>：把流式片段写进 <code>ai_message.content</code>，
 *       校验失败时库里留下一份"看起来是报告"的残缺文本。
 * </ol>
 *
 * <p>存储与事件流用手写内存桩而不是 Mockito：本类要断言的是**写入的内容**
 * （事件序列、报告字段、消息条数），而 {@code verify} 只能数调用次数。
 */
class AiTaskExecutionServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-20T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final long TASK_ID = 7001L;
    private static final long SESSION_ID = 5001L;
    private static final long USER_ID = 1001L;
    private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-09-18T15:00:00+08:00");
    private static final String SECURITY_ID = "sim-600519";
    private static final long SECURITY_STORAGE_ID = 600_519L;

    private final InMemoryTaskStore tasks = new InMemoryTaskStore();
    private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
    private final InMemoryMessageStore messages = new InMemoryMessageStore();
    private final InMemoryReportStore reports = new InMemoryReportStore();
    private final InMemoryEventStream events = new InMemoryEventStream();
    private final RecordingQueue queue = new RecordingQueue();
    private final AiContextBuilder contextBuilder = mock(AiContextBuilder.class);
    private final SecurityIdentityProvider securities = mock(SecurityIdentityProvider.class);
    private final SectorIdentityProvider sectors = mock(SectorIdentityProvider.class);
    private final ScriptedLlm llm = new ScriptedLlm();

    /** 两个抽象方法，所以不是函数接口，只能写成匿名类。 */
    private final AiContentHasher hasher = new AiContentHasher() {
        @Override
        public String hashOf(java.util.Map<String, ?> content) {
            return hashOfText(String.valueOf(content));
        }

        @Override
        public String hashOfText(String text) {
            return "h" + Integer.toHexString(String.valueOf(text).hashCode());
        }
    };
    private final AtomicLong ids = new AtomicLong(10_000L);

    @BeforeEach
    void seedTaskAndContext() {
        tasks.insert(task(AiTaskStatus.QUEUED));
        when(contextBuilder.build(any(), any(), any())).thenReturn(context(List.of(), true));
        // 主数据：让还原器能把代理键 600519 还原成对外标识 sim-600519
        when(securities.findByStorageIds(any())).thenAnswer(invocation -> {
            Set<Long> asked = invocation.getArgument(0);
            return asked.contains(SECURITY_STORAGE_ID)
                    ? Map.of(SECURITY_STORAGE_ID, new SecurityIdentity(
                            SECURITY_STORAGE_ID, AiFixtures.security("600519", "模拟证券600519")))
                    : Map.of();
        });
        when(sectors.findByStorageIds(any())).thenReturn(Map.of());
    }

    private AiTaskExecutionService service() {
        return new AiTaskExecutionService(
                tasks,
                snapshots,
                messages,
                reports,
                events,
                queue,
                contextBuilder,
                new AiTargetHydrator(securities, sectors),
                llm,
                hasher,
                ids::incrementAndGet,
                CLOCK,
                "p1",
                "v1",
                2048);
    }

    /**
     * 取数之前必须把"库里的目标"还原成带对外标识的形状。
     *
     * <p>这条不变量此前**完全没有测试守着**，而它一旦破了，每一个任务都会失败：
     * {@code ai_task_target} 只存 bigint 代理键，从库里读回来的 {@code targetId} 是
     * {@code null}，而 {@code AiContextBuilder} 要拿它去取行情——
     * {@code batch.snapshotOf(null)} 会在不可变 Map 上抛
     * {@code NullPointerException}，任务以 {@code AI_CONTEXT_BUILD_FAILED} 结束。
     *
     * <p>它之所以一直没被发现，是因为本类的 {@code contextBuilder} 是 Mockito 桩：
     * 无论喂进去什么目标都返回同一份预置结果。所以这里必须**捕获实参**来断言，
     * 而不是断言结果。
     */
    @Test
    @DisplayName("取数前把目标还原成带对外标识的形状（库里读回来的是 null）")
    void hydratesTargetsBeforeBuildingTheContext() {
        assertThat(tasks.find(TASK_ID).orElseThrow().targets().get(0).targetId())
                .describedAs("前提：存储层读回来的目标没有对外标识")
                .isNull();

        llm.completion = completionOf(validSections());
        service().execute(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiContextTarget>> captor = ArgumentCaptor.forClass(List.class);
        verify(contextBuilder).build(captor.capture(), any(), any());
        assertThat(captor.getValue())
                .extracting(AiContextTarget::targetId)
                .describedAs("喂给上下文构建器的必须是还原后的对外标识")
                .containsExactly(SECURITY_ID);
    }

    // ---------- 抢执行权 ----------

    @Test
    @DisplayName("抢不到执行权：直接跳过，且一次 LLM 都不调（不重复计费）")
    void skipsWhenClaimFails() {
        tasks.forceStatus(TASK_ID, AiTaskStatus.RUNNING);

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.SKIPPED);
        assertThat(llm.calls).isZero();
        assertThat(reports.rows).isEmpty();
    }

    @Test
    @DisplayName("任务不存在：跳过而不是抛异常（消息可能指向已被清理的任务）")
    void skipsWhenTaskMissing() {
        assertThat(service().execute(999_999L)).isEqualTo(AiTaskExecutionService.Outcome.SKIPPED);
        assertThat(llm.calls).isZero();
    }

    @Test
    @DisplayName("已置取消意图：兑现为 CANCELED，不调 LLM")
    void honorsCancelIntentBeforeCallingLlm() {
        tasks.rows.get(0).cancelRequested = true;

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.CANCELED);
        assertThat(llm.calls).isZero();
        assertThat(tasks.rows.get(0).status).isEqualTo(AiTaskStatus.CANCELED);
        assertThat(tasks.rows.get(0).completedAt).isNotNull();
    }

    // ---------- 正常链路 ----------

    @Test
    @DisplayName("正常链路：状态推进 + 六类事件齐备 + 报告可按 taskId 查到")
    void completesHappyPath() {
        llm.completion = completionOf(Map.of(
                AiReportSection.CORE_CONCLUSION, "综合 1 条证据 [1]。",
                AiReportSection.QUOTE_EVIDENCE, "行情依据 [1]。",
                AiReportSection.RISK_AND_UNCERTAINTY, "数据可能滞后。",
                AiReportSection.DISCLAIMER, "本内容不构成投资建议。"));

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.COMPLETED);

        Row done = tasks.rows.get(0);
        assertThat(done.status).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(done.completedAt).isNotNull();
        assertThat(done.firstChunkAt).isNotNull();
        assertThat(done.attemptNo).isEqualTo(1);
        // 报告与任务的关联存在 ai_report.task_id（唯一索引），任务这一侧没有 report_id 列，
        // 所以"报告能被查到"就是这里唯一该断言的事。
        assertThat(reports.findByTask(TASK_ID)).isPresent();

        // 状态事件按状态机顺序，且每条 chunk 都落在事件流里
        assertThat(events.typesOf(TASK_ID))
                .startsWith(AiTaskEventType.STATUS)
                .endsWith(AiTaskEventType.REPORT, AiTaskEventType.DONE)
                .contains(AiTaskEventType.CHUNK);

        assertThat(reports.rows).hasSize(1);
        AiReport report = reports.rows.get(0);
        assertThat(report.taskId()).isEqualTo(TASK_ID);
        assertThat(report.quality()).isEqualTo(AiReportQuality.VALID);
        assertThat(report.marketDataCutoffAt()).isEqualTo(CUTOFF);
        assertThat(report.newsDataCutoffAt()).isNull();
        assertThat(report.renderedMarkdown()).contains("## 核心结论").contains("## 免责声明");
        assertThat(report.contentHash()).isNotBlank().hasSize(9);
    }

    @Test
    @DisplayName("内容哈希：改掉任意一个章节，哈希就变（六章节全部参与）")
    void contentHashCoversEverySection() {
        llm.completion = completionOf(validSections());
        service().execute(TASK_ID);
        String baseline = reports.rows.get(0).contentHash();

        reports.rows.clear();
        tasks.rows.get(0).status = AiTaskStatus.QUEUED;
        events.byTask.clear();
        Map<AiReportSection, String> changed = new LinkedHashMap<>(validSections());
        changed.put(AiReportSection.DISCLAIMER, "免责。改了这句。");
        llm.completion = completionOf(changed);
        service().execute(TASK_ID);

        assertThat(reports.rows.get(0).contentHash()).isNotEqualTo(baseline);
    }

    @Test
    @DisplayName("正常链路：助手消息只写一条，且内容就是最终 markdown（片段不落库）")
    void writesExactlyOneAssistantMessage() {
        llm.completion = completionOf(Map.of(
                AiReportSection.CORE_CONCLUSION, "结论。",
                AiReportSection.QUOTE_EVIDENCE, "依据。",
                AiReportSection.RISK_AND_UNCERTAINTY, "风险。",
                AiReportSection.DISCLAIMER, "免责。"));

        service().execute(TASK_ID);

        assertThat(messages.rows).hasSize(1);
        AiMessage message = messages.rows.get(0);
        assertThat(message.roleType()).isEqualTo(AiMessageRole.ASSISTANT);
        assertThat(message.taskId()).isEqualTo(TASK_ID);
        assertThat(message.content()).isEqualTo(reports.rows.get(0).renderedMarkdown());
        assertThat(reports.rows.get(0).assistantMessageId()).isEqualTo(message.messageId());
    }

    @Test
    @DisplayName("正常链路：上下文快照按 snapshot_no 连续写入")
    void persistsContextSnapshots() {
        when(contextBuilder.build(any(), any(), any())).thenReturn(context(List.of(), true));
        llm.completion = completionOf(validSections());

        service().execute(TASK_ID);

        assertThat(snapshots.snapshotNos(TASK_ID)).containsExactly(1, 2);
    }

    // ---------- 失败路径 ----------

    @Test
    @DisplayName("核心行情缺失：FAILED / AI_CORE_DATA_MISSING / DATA，且不写报告")
    void failsWhenCoreDataMissing() {
        when(contextBuilder.build(any(), any(), any()))
                .thenReturn(new AiContextBuildResult(
                        List.of(), List.of(), List.of("行情快照暂不可用"), false));

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.FAILED);

        Row failed = tasks.rows.get(0);
        assertThat(failed.status).isEqualTo(AiTaskStatus.FAILED);
        assertThat(failed.errorCategory).isEqualTo(LlmErrorCategory.DATA.name());
        assertThat(failed.errorCode).isEqualTo("AI_CORE_DATA_MISSING");
        assertThat(failed.errorMessage).contains("行情快照暂不可用");
        assertThat(failed.completedAt).isNotNull();
        assertThat(llm.calls).isZero();
        assertThat(reports.rows).isEmpty();
        assertThat(events.typesOf(TASK_ID)).endsWith(AiTaskEventType.ERROR, AiTaskEventType.DONE);
    }

    @Test
    @DisplayName("引用越界：FAILED / AI_OUTPUT_REJECTED / SAFETY，且不写报告")
    void rejectsOutOfRangeCitation() {
        llm.completion = completionOf(Map.of(
                AiReportSection.CORE_CONCLUSION, "结论引用了 [7]，但候选集合里只有 [1]。",
                AiReportSection.QUOTE_EVIDENCE, "依据 [1]。",
                AiReportSection.RISK_AND_UNCERTAINTY, "风险。",
                AiReportSection.DISCLAIMER, "免责。"));

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.FAILED);

        Row failed = tasks.rows.get(0);
        assertThat(failed.errorCode).isEqualTo("AI_OUTPUT_REJECTED");
        assertThat(failed.errorCategory).isEqualTo(LlmErrorCategory.SAFETY.name());
        assertThat(failed.errorMessage).contains("7");
        assertThat(reports.rows).isEmpty();
        assertThat(messages.rows).isEmpty();
    }

    @Test
    @DisplayName("缺必填章节：FAILED / AI_OUTPUT_REJECTED，错误信息点名缺哪一章")
    void rejectsMissingRequiredSection() {
        llm.completion = completionOf(Map.of(
                AiReportSection.CORE_CONCLUSION, "结论 [1]。",
                AiReportSection.QUOTE_EVIDENCE, "依据 [1]。"));

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.FAILED);

        assertThat(tasks.rows.get(0).errorCode).isEqualTo("AI_OUTPUT_REJECTED");
        assertThat(tasks.rows.get(0).errorMessage)
                .contains(AiReportSection.RISK_AND_UNCERTAINTY.name())
                .contains(AiReportSection.DISCLAIMER.name());
        assertThat(reports.rows).isEmpty();
    }

    @Test
    @DisplayName("超时：超过 deadline_at 的任务置 TIMED_OUT，不调 LLM")
    void timesOutPastDeadline() {
        tasks.rows.get(0).deadlineAt = OffsetDateTime.now(CLOCK).minusSeconds(1);

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.TIMED_OUT);

        assertThat(tasks.rows.get(0).status).isEqualTo(AiTaskStatus.TIMED_OUT);
        assertThat(tasks.rows.get(0).completedAt).isNotNull();
        assertThat(llm.calls).isZero();
    }

    // ---------- 重试语义 ----------

    @Test
    @DisplayName("可重试的供应商异常：回到 QUEUED 并重新入队，不算失败")
    void requeuesRetryableProviderFailure() {
        llm.failure = LlmProviderException.rateLimited("模拟限流");

        assertThat(service().execute(TASK_ID))
                .isEqualTo(AiTaskExecutionService.Outcome.RETRY_SCHEDULED);

        Row retried = tasks.rows.get(0);
        assertThat(retried.status).isEqualTo(AiTaskStatus.QUEUED);
        assertThat(retried.completedAt).isNull();
        assertThat(queue.enqueued).containsExactly(TASK_ID);
        assertThat(events.typesOf(TASK_ID)).doesNotContain(AiTaskEventType.DONE);
    }

    @Test
    @DisplayName("可重试但次数已用尽：FAILED，不再入队")
    void failsWhenAttemptsExhausted() {
        tasks.rows.get(0).attemptNo = 1;
        tasks.rows.get(0).maxAttempts = 2;
        llm.failure = LlmProviderException.timeout("模拟超时");

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.FAILED);

        assertThat(tasks.rows.get(0).status).isEqualTo(AiTaskStatus.FAILED);
        assertThat(tasks.rows.get(0).errorCode).isEqualTo("AI_TASK_TIMED_OUT");
        assertThat(tasks.rows.get(0).errorCategory).isEqualTo(LlmErrorCategory.TIMEOUT.name());
        assertThat(queue.enqueued).isEmpty();
        assertThat(events.typesOf(TASK_ID)).endsWith(AiTaskEventType.ERROR, AiTaskEventType.DONE);
    }

    @Test
    @DisplayName("不可重试的供应商异常：直接 FAILED（重试可能造成伤害）")
    void doesNotRetrySafetyRejection() {
        llm.failure = LlmProviderException.safetyRejected("命中禁用表达");

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.FAILED);

        assertThat(tasks.rows.get(0).status).isEqualTo(AiTaskStatus.FAILED);
        assertThat(tasks.rows.get(0).errorCategory).isEqualTo(LlmErrorCategory.SAFETY.name());
        assertThat(queue.enqueued).isEmpty();
    }

    // ---------- 受限报告 ----------

    @Test
    @DisplayName("资讯缺失：产出 LIMITED 报告而不是失败，且 limitedReason 非空")
    void producesLimitedReportWhenLimitationsExist() {
        when(contextBuilder.build(any(), any(), any()))
                .thenReturn(context(List.of("暂无可用资讯，事件线索缺失"), true));
        llm.completion = completionOf(validSections());

        assertThat(service().execute(TASK_ID)).isEqualTo(AiTaskExecutionService.Outcome.COMPLETED);

        AiReport report = reports.rows.get(0);
        assertThat(report.quality()).isEqualTo(AiReportQuality.LIMITED);
        assertThat(report.limited()).isTrue();
        assertThat(report.limitedReason()).contains("暂无可用资讯");
        assertThat(report.qualityStatus()).isEqualTo("LIMITED");
    }

    // ---------- 构造器与夹具 ----------

    private static Map<AiReportSection, String> validSections() {
        Map<AiReportSection, String> sections = new LinkedHashMap<>();
        sections.put(AiReportSection.CORE_CONCLUSION, "结论 [1]。");
        sections.put(AiReportSection.QUOTE_EVIDENCE, "依据 [1]。");
        sections.put(AiReportSection.RISK_AND_UNCERTAINTY, "风险。");
        sections.put(AiReportSection.DISCLAIMER, "免责。");
        return sections;
    }

    private static AiContextBuildResult context(List<String> limitations, boolean coreAvailable) {
        List<AiContextSnapshot> snapshotList = new ArrayList<>();
        snapshotList.add(new AiContextSnapshot(
                1,
                AiContextType.QUOTE,
                "SECURITY",
                600519L,
                "security:600519",
                CUTOFF,
                CUTOFF,
                "hash-quote",
                Map.of("securityId", "sim-600519"),
                true));
        snapshotList.add(new AiContextSnapshot(
                2,
                AiContextType.SECTOR,
                "SECTOR",
                88L,
                "sector:88",
                CUTOFF,
                CUTOFF,
                "hash-sector",
                Map.of("sectorId", "bk-ai"),
                false));
        List<AiEvidenceCandidate> evidence = List.of(new AiEvidenceCandidate(
                1,
                AiEvidenceType.QUOTE,
                "SECURITY",
                600519L,
                "模拟证券600519",
                null,
                "最新价 10.00，涨跌幅 +1.00%",
                CUTOFF,
                CUTOFF,
                cn.zhishi.stock.ai.domain.AiEvidenceAccessStatus.AVAILABLE,
                "hash-evidence"));
        return new AiContextBuildResult(snapshotList, evidence, limitations, coreAvailable);
    }

    /** 按章节拼出片段序列，每章一段（足够覆盖校验逻辑）。 */
    private static LlmCompletion completionOf(Map<AiReportSection, String> sections) {
        List<LlmChunk> chunks = new ArrayList<>();
        int sequence = 1;
        for (AiReportSection section : AiReportSection.inOrder()) {
            String body = sections.get(section);
            if (body == null) {
                continue;
            }
            chunks.add(new LlmChunk(section, body, sequence++));
        }
        return new LlmCompletion(
                "sim-req-1",
                "sim-analyst-v1",
                chunks,
                new LlmUsage(10, 20, 0, 30),
                Duration.ofMillis(100),
                Duration.ofMillis(900));
    }

    private static AiTask task(AiTaskStatus status) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new AiTask(
                TASK_ID,
                "11111111-2222-3333-4444-555555555555",
                SESSION_ID,
                USER_ID,
                null,
                AiScene.STOCK,
                "怎么看",
                now.minusDays(1),
                now,
                status,
                false,
                0,
                2,
                "SIMULATED",
                "sim-analyst-v1",
                "trace-1",
                now.minusSeconds(1),
                now.minusSeconds(1),
                null,
                null,
                null,
                null,
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

    // ---------- 内存桩 ----------

    /** 可变行：测试直接改字段来表达"任务已被别人抢走""已过截止时间"等前置状态。 */
    private static final class Row {
        final long taskId;
        final long sessionId = SESSION_ID;
        final long userId = USER_ID;
        AiTaskStatus status;
        boolean cancelRequested;
        int attemptNo;
        int maxAttempts = 2;
        OffsetDateTime deadlineAt;
        OffsetDateTime completedAt;
        OffsetDateTime firstChunkAt;
        String errorCategory;
        String errorCode;
        String errorMessage;
        int version;

        Row(AiTask task) {
            this.taskId = task.taskId();
            this.status = task.status();
            this.attemptNo = task.attemptNo();
            this.maxAttempts = task.maxAttempts();
            this.deadlineAt = task.deadlineAt();
        }

        AiTask toTask() {
            OffsetDateTime now = OffsetDateTime.now(CLOCK);
            return new AiTask(
                    taskId,
                    "11111111-2222-3333-4444-555555555555",
                    sessionId,
                    userId,
                    null,
                    AiScene.STOCK,
                    "怎么看",
                    now.minusDays(1),
                    now,
                    status,
                    cancelRequested,
                    attemptNo,
                    maxAttempts,
                    "SIMULATED",
                    "sim-analyst-v1",
                    "trace-1",
                    now.minusSeconds(1),
                    now.minusSeconds(1),
                    null,
                    firstChunkAt,
                    null,
                    null,
                    deadlineAt,
                    completedAt,
                    errorCategory,
                    errorCode,
                    errorMessage,
                    version,
                    // 与 MyBatisAiTaskStore 同形：ai_task_target 只存 bigint 代理键与代码快照，
                    // **不存对外标识**。这里必须如实抹掉它，否则"执行器忘了还原对外标识"
                    // 这类缺陷在单测里永远看不出来（它正是靠真 Redis/MySQL 集成测试才暴露的）。
                    List.of(new AiContextTarget(
                            AiTargetType.SECURITY,
                            null,
                            "600519",
                            "模拟证券600519",
                            AiTargetRole.PRIMARY,
                            600519L)));
        }
    }

    private static final class InMemoryTaskStore implements AiTaskStore {
        final List<Row> rows = new ArrayList<>();

        @Override
        public Optional<AiTask> find(long taskId) {
            return rows.stream().filter(row -> row.taskId == taskId).findFirst().map(Row::toTask);
        }

        @Override
        public Optional<AiTask> findByRequestId(String requestId) {
            return Optional.empty();
        }

        @Override
        public void insert(AiTask task) {
            rows.add(new Row(task));
        }

        @Override
        public Optional<AiTask> save(AiTask task) {
            Row row = rows.get(0);
            row.status = task.status();
            row.attemptNo = task.attemptNo();
            row.completedAt = task.completedAt();
            row.firstChunkAt = task.firstChunkAt();
            row.errorCategory = task.errorCategory();
            row.errorCode = task.errorCode();
            row.errorMessage = task.errorMessage();
            row.cancelRequested = task.cancelRequested();
            // 推进由"数据库"做，并把新版本交回给调用方（与 MyBatisAiTaskStore 同形）。
            // 桩若只返回 true，被测代码里"拿旧版本继续写"的缺陷就会被掩盖。
            row.version++;
            return Optional.of(task.withVersion(task.version() + 1));
        }

        @Override
        public boolean claimForExecution(long taskId, OffsetDateTime at) {
            Row row = rows.get(0);
            if (row.status != AiTaskStatus.QUEUED && row.status != AiTaskStatus.CREATED) {
                return false;
            }
            if (row.cancelRequested) {
                return false;
            }
            row.status = AiTaskStatus.PREPARING;
            row.attemptNo++;
            return true;
        }

        @Override
        public boolean requestCancel(long taskId) {
            rows.get(0).cancelRequested = true;
            return true;
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

        @Override
        public List<AiTask> findRecoverable(
                OffsetDateTime queuedBefore, OffsetDateTime heartbeatBefore, int limit) {
            return List.of();
        }

        void forceStatus(long taskId, AiTaskStatus status) {
            rows.get(0).status = status;
        }
    }

    private static final class InMemorySnapshotStore implements AiContextSnapshotStore {
        final List<AiContextSnapshot> rows = new ArrayList<>();

        @Override
        public void insertAll(long taskId, List<AiContextSnapshot> snapshots) {
            rows.addAll(snapshots);
        }

        @Override
        public int countByTask(long taskId) {
            return rows.size();
        }

        List<Integer> snapshotNos(long taskId) {
            return rows.stream().map(AiContextSnapshot::snapshotNo).sorted().toList();
        }
    }

    private static final class InMemoryMessageStore implements AiMessageStore {
        final List<AiMessage> rows = new ArrayList<>();

        @Override
        public int nextSequenceNo(long sessionId) {
            return rows.size() + 1;
        }

        @Override
        public void insert(AiMessage message) {
            // 原样存：messageId 由用例层分配（与 MyBatisAiMessageStore 一致）
            rows.add(message);
        }

        @Override
        public int countBySession(long sessionId) {
            return rows.size();
        }

        /* HIS-05 的可见消息查询本类用不到。刻意抛异常而不是返回空列表——
         * 空列表可能让一个本该失败的断言因为"没有消息"而通过。 */
        @Override
        public List<AiMessage> listVisible(long sessionId, int offset, int limit) {
            throw new UnsupportedOperationException("本测试不覆盖会话消息查询");
        }

        @Override
        public int countVisible(long sessionId) {
            throw new UnsupportedOperationException("本测试不覆盖会话消息查询");
        }
    }

    private static final class InMemoryReportStore implements AiReportStore {
        final List<AiReport> rows = new ArrayList<>();

        @Override
        public void insert(AiReport report) {
            // 原样存：reportId 由用例层分配（与 MyBatisAiReportStore 一致）
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

    private static final class InMemoryEventStream implements AiTaskEventStream {
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
            return byTask.getOrDefault(taskId, List.of()).stream()
                    .filter(event -> event.sequence() > lastSequence)
                    .limit(count)
                    .toList();
        }

        @Override
        public Optional<Long> latestSequence(long taskId) {
            List<AiTaskEvent> list = byTask.getOrDefault(taskId, List.of());
            return list.isEmpty()
                    ? Optional.empty()
                    : Optional.of(list.get(list.size() - 1).sequence());
        }

        List<AiTaskEventType> typesOf(long taskId) {
            return byTask.getOrDefault(taskId, List.of()).stream()
                    .map(AiTaskEvent::type)
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .toList();
        }
    }

    private static final class RecordingQueue implements AiTaskQueue {
        final List<Long> enqueued = new ArrayList<>();

        @Override
        public void enqueue(long taskId) {
            enqueued.add(taskId);
        }

        @Override
        public List<cn.zhishi.stock.ai.domain.AiTaskQueueMessage> receive(int count) {
            return List.of();
        }

        @Override
        public void ack(String messageId) {
        }
    }

    /** 可编排的 LLM 桩：要么给出固定结果，要么抛指定异常。 */
    private static final class ScriptedLlm implements LlmProviderPort {
        LlmCompletion completion;
        LlmProviderException failure;
        int calls;

        @Override
        public LlmCompletion complete(LlmRequest request, Consumer<LlmChunk> onChunk) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            for (LlmChunk chunk : completion.chunks()) {
                onChunk.accept(chunk);
            }
            return completion;
        }
    }
}
