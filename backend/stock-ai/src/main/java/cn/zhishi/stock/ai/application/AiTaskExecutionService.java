package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.AiContextBuildResult;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiContextType;
import cn.zhishi.stock.ai.domain.AiDataCutoff;
import cn.zhishi.stock.ai.domain.AiEvidence;
import cn.zhishi.stock.ai.domain.AiEvidenceCandidate;
import cn.zhishi.stock.ai.domain.AiEvidenceStore;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiPromptRenderer;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportQuality;
import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiReportText;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.LlmChunk;
import cn.zhishi.stock.ai.domain.LlmCompletion;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import cn.zhishi.stock.ai.domain.LlmEvidence;
import cn.zhishi.stock.ai.domain.LlmProviderException;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.domain.LlmRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行一个 AI 任务（M3-07 的核心用例）。
 *
 * <h2>防重复执行靠「抢执行权」，不靠「读过就认为在做」</h2>
 * 队列语义是至少一次，所以同一个任务可能被投递两次、也可能被两个实例同时收到。
 * 唯一的互斥是 {@link AiTaskStore#claimForExecution} 的条件更新：拿到 1 行才算抢到。
 * 抢不到就**一次 LLM 都不调**——否则"至少一次消费"会变成"至少一次计费"。
 *
 * <h2>每一步之间都重新读库</h2>
 * {@code cancel_requested} 与 {@code deadline_at} 都是**别人写的**（用户点了取消、
 * 另一个实例判了超时）。手上那份聚合可能在取数的几秒里已经过期，
 * 所以每个检查点都重新读一次，而不是相信进入方法时的那一份。
 *
 * <h2>片段只进 Redis，不进 MySQL</h2>
 * 契约 §13.4 明确：临时片段可能因为最终结构、引用或安全校验失败而不形成报告，
 * 前端必须以 {@code report} 事件或任务 {@code COMPLETED} 为成功依据。
 * 所以 {@code ai_message.content} 只在定稿时写一次；把片段写进去会让校验失败的任务
 * 在库里留下一份"看起来是报告"的残缺文本。
 *
 * <h2>失败不是终点：可重试的供应商异常回到队列</h2>
 * {@code TIMEOUT} / {@code RATE_LIMIT} / {@code PROVIDER} 三类值得再试一次
 * （{@link LlmErrorCategory#retryable()}），由 {@code attempt_no < max_attempts} 封顶。
 * {@code SAFETY} / {@code DATA} / {@code SYSTEM} 不重试——重试对它们没有意义，
 * 而安全拒绝再试一次可能造成伤害。
 */
public class AiTaskExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTaskExecutionService.class);

    /** 一次执行的结果。调用方（消费者）据此决定 ACK 还是留给恢复扫描。 */
    public enum Outcome {
        /** 没抢到执行权（别人在做 / 已是终态 / 已被取消）或任务不存在。 */
        SKIPPED,
        COMPLETED,
        FAILED,
        TIMED_OUT,
        CANCELED,
        /** 可重试的失败，已重新入队等待下一次尝试。 */
        RETRY_SCHEDULED
    }

    private final AiTaskStore tasks;
    private final AiContextSnapshotStore snapshots;
    private final AiMessageStore messages;
    private final AiReportStore reports;
    private final AiEvidenceStore evidences;
    private final AiTaskEventStream events;
    private final AiTaskQueue queue;
    private final AiContextBuilder contextBuilder;
    private final AiTargetHydrator targetHydrator;
    private final LlmProviderPort llm;
    private final AiContentHasher hasher;
    private final LongSupplier idGenerator;
    private final Clock clock;
    private final String promptVersion;
    private final String contentSchemaVersion;
    private final int maxOutputTokens;

    public AiTaskExecutionService(
            AiTaskStore tasks,
            AiContextSnapshotStore snapshots,
            AiMessageStore messages,
            AiReportStore reports,
            AiEvidenceStore evidences,
            AiTaskEventStream events,
            AiTaskQueue queue,
            AiContextBuilder contextBuilder,
            AiTargetHydrator targetHydrator,
            LlmProviderPort llm,
            AiContentHasher hasher,
            LongSupplier idGenerator,
            Clock clock,
            String promptVersion,
            String contentSchemaVersion,
            int maxOutputTokens) {
        this.tasks = tasks;
        this.snapshots = snapshots;
        this.messages = messages;
        this.reports = reports;
        this.evidences = evidences;
        this.events = events;
        this.queue = queue;
        this.contextBuilder = contextBuilder;
        this.targetHydrator = targetHydrator;
        this.llm = llm;
        this.hasher = hasher;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.promptVersion = promptVersion;
        this.contentSchemaVersion = contentSchemaVersion;
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * 尝试执行一个任务。
     *
     * <p>幂等：对同一个已完成的任务再调一次会返回 {@link Outcome#SKIPPED}，不会产生第二个报告。
     */
    public Outcome execute(long taskId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AiTask candidate = tasks.find(taskId).orElse(null);
        if (candidate == null) {
            // 消息指向一个已被清理的任务：ACK 掉它，别留在 pending 里反复重投。
            LOGGER.warn("AI 任务不存在，跳过：taskId={}", taskId);
            return Outcome.SKIPPED;
        }
        if (!claimable(candidate.status())) {
            return Outcome.SKIPPED;
        }
        if (candidate.cancelRequested()) {
            return finishCanceled(candidate, now);
        }
        if (candidate.pastDeadline(now)) {
            return finishTimedOut(candidate, now);
        }
        if (!tasks.claimForExecution(taskId, now)) {
            // 别人抢到了 / 刚被置了取消意图 / 刚被判超时。三种情况都不该再动它。
            return Outcome.SKIPPED;
        }
        AiTask claimed = tasks.find(taskId).orElseThrow(() -> new IllegalStateException(
                "抢占执行权后任务消失：taskId=" + taskId));
        return run(claimed);
    }

    // ---------- 执行流水线 ----------

    private Outcome run(AiTask claimed) {
        emitStatus(claimed, AiTaskStatus.PREPARING);

        AiContextBuildResult context;
        try {
            // 必须先还原对外标识：从库里读回来的目标只有 bigint 代理键
            // （ai_task_target 不存 sim-600519 那种对外标识），而 AiContextBuilder
            // 要用它去取行情。少了这一步，batch.snapshotOf(null) 会在不可变 Map 上抛
            // NullPointerException，**每一个任务都会以 AI_CONTEXT_BUILD_FAILED 失败**——
            // 而单测里的 contextBuilder 是桩，喂什么进去都返回同一份预置结果，看不见。
            context = contextBuilder.build(
                    targetHydrator.hydrate(claimed.targets()),
                    claimed.analysisStartAt(),
                    claimed.analysisEndAt());
        } catch (RuntimeException exception) {
            return finishFailed(
                    claimed,
                    LlmErrorCategory.SYSTEM,
                    "AI_CONTEXT_BUILD_FAILED",
                    describe(exception));
        }
        if (!context.coreDataAvailable()) {
            return finishFailed(
                    claimed, LlmErrorCategory.DATA, "AI_CORE_DATA_MISSING", reasonOf(context));
        }
        snapshots.insertAll(claimed.taskId(), context.snapshots());

        AiTask task = reload(claimed.taskId());
        Outcome stopped = stopIfInterrupted(task);
        if (stopped != null) {
            return stopped;
        }

        Optional<AiTask> advanced = advance(task, AiTaskStatus.RUNNING);
        if (advanced.isEmpty()) {
            return Outcome.SKIPPED;
        }
        AiTask running = advanced.get();

        Cursor cursor = new Cursor(running);
        LlmCompletion completion;
        try {
            completion = llm.complete(requestOf(running, context), chunk -> onChunk(cursor, chunk));
        } catch (LlmProviderException exception) {
            return onProviderFailure(reload(cursor.task.taskId()), exception);
        } catch (RuntimeException exception) {
            return finishFailed(
                    reload(cursor.task.taskId()),
                    LlmErrorCategory.SYSTEM,
                    "AI_PROVIDER_FAILED",
                    describe(exception));
        }

        AiTask afterGeneration = reload(cursor.task.taskId());
        stopped = stopIfInterrupted(afterGeneration);
        if (stopped != null) {
            return stopped;
        }

        Optional<AiTask> validating = advance(afterGeneration, AiTaskStatus.VALIDATING);
        if (validating.isEmpty()) {
            return Outcome.SKIPPED;
        }

        Optional<String> rejection =
                validationFailure(validating.get(), completion, context.evidenceCandidates());
        if (rejection.isPresent()) {
            return finishFailed(
                    reload(validating.get().taskId()),
                    LlmErrorCategory.SAFETY,
                    "AI_OUTPUT_REJECTED",
                    rejection.get());
        }
        return finishCompleted(validating.get(), completion, context);
    }

    /**
     * 片段到达：先写事件流，再刷新 {@code first_chunk_at} / {@code heartbeat_at}。
     *
     * <p>顺序是刻意的：事件流写失败会让 SSE 少一段，而心跳写失败只会让恢复扫描
     * 早一点怀疑这个执行者。反过来（先刷心跳后写事件）会掩盖真正的故障。
     */
    private void onChunk(Cursor cursor, LlmChunk chunk) {
        AiTask task = cursor.task;
        events.append(task.taskId(), AiTaskEventType.CHUNK, sequence -> AiTaskEventPayloads.chunk(
                task.taskId(), chunk.section(), chunk.delta(), sequence));

        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<AiTask> refreshed = tasks.save(task.recordFirstChunk(now).heartbeat(now));
        if (refreshed.isPresent()) {
            // 换用写入后的那一份：数据库已经推进了 version，继续用手上这份会写不进去。
            cursor.task = refreshed.get();
        } else {
            // 乐观锁冲突只丢一次心跳刷新，不中断生成：生成中断的代价更大，
            // 而心跳有 30 秒量级的恢复阈值兜着。
            LOGGER.warn("刷新心跳被乐观锁拒绝：taskId={}", task.taskId());
        }
    }

    private Outcome onProviderFailure(AiTask task, LlmProviderException exception) {
        LlmErrorCategory category = exception.category();
        if (category.retryable() && task.canAttemptAgain()) {
            return requeueForRetry(task);
        }
        return finishFailed(task, category, exception.errorCode(), exception.getMessage());
    }

    private Outcome requeueForRetry(AiTask task) {
        AiTask requeued = task.requeued(OffsetDateTime.now(clock));
        if (tasks.save(requeued).isEmpty()) {
            return Outcome.SKIPPED;
        }
        // 入队失败不回滚状态：任务已是 QUEUED，恢复扫描会把它重新投出去。
        // 反过来（先入队后写状态）会出现"Worker 拿到一个状态还是 RUNNING 的任务"。
        queue.enqueue(task.taskId());
        LOGGER.info(
                "AI 任务将在下一次尝试中重跑：taskId={} attempt={}/{}",
                task.taskId(), task.attemptNo(), task.maxAttempts());
        return Outcome.RETRY_SCHEDULED;
    }

    // ---------- 校验 ----------

    /**
     * 定稿校验。返回非空表示拒绝，内容会作为 {@code error_message} 落库。
     *
     * <p>两项：必填章节齐全、引用编号全部落在**本任务固化的候选集合**内。
     */
    private Optional<String> validationFailure(
            AiTask task, LlmCompletion completion, List<AiEvidenceCandidate> candidates) {
        List<AiReportSection> missing =
                AiReportText.missingRequired(task.scene(), completion.sections());
        if (!missing.isEmpty()) {
            return Optional.of("报告缺少必填章节：" + missing);
        }
        Set<Integer> allowed = new LinkedHashSet<>();
        for (AiEvidenceCandidate candidate : candidates) {
            allowed.add(candidate.evidenceNo());
        }
        for (AiReportSection section : completion.sections()) {
            Set<Integer> cited = AiReportText.citationsIn(completion.textOf(section));
            Set<Integer> illegal = new LinkedHashSet<>(cited);
            illegal.removeAll(allowed);
            if (!illegal.isEmpty()) {
                return Optional.of(
                        "章节 " + section.name() + " 引用了候选集合外的证据编号：" + illegal);
            }
        }
        return Optional.empty();
    }

    // ---------- 定稿 ----------

    private Outcome finishCompleted(
            AiTask task, LlmCompletion completion, AiContextBuildResult context) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long reportId = idGenerator.getAsLong();

        Long assistantMessageId = null;
        if (task.question() != null || !completion.chunks().isEmpty()) {
            assistantMessageId = writeAssistantMessage(task, completion, context, now);
        }
        AiReport report = composeReport(task, completion, context, reportId, assistantMessageId, now);
        reports.insert(report);
        persistEvidence(report, context, now);

        AiTask completed = task.complete(now);
        if (tasks.save(completed).isEmpty()) {
            // 报告已经落库了，但任务状态没能推进。恢复扫描会把任务重新捞起来，
            // 而 uk_ai_report_task 会让第二次插入失败——不会产生两份报告。
            // 报告与任务的关联在 ai_report.task_id 上，所以这里不需要（也无处）回写。
            LOGGER.error("报告已写入但任务状态推进失败：taskId={} reportId={}", task.taskId(), reportId);
            return Outcome.SKIPPED;
        }
        events.append(
                task.taskId(),
                AiTaskEventType.REPORT,
                sequence -> AiTaskEventPayloads.report(task.taskId(), report, sequence));
        events.append(
                task.taskId(),
                AiTaskEventType.DONE,
                sequence -> AiTaskEventPayloads.done(
                        task.taskId(), AiTaskStatus.COMPLETED, sequence));
        return Outcome.COMPLETED;
    }

    private Long writeAssistantMessage(
            AiTask task, LlmCompletion completion, AiContextBuildResult context, OffsetDateTime now) {
        long messageId = idGenerator.getAsLong();
        messages.insert(new AiMessage(
                messageId,
                task.sessionId(),
                task.taskId(),
                AiMessageRole.ASSISTANT,
                messages.nextSequenceNo(task.sessionId()),
                AiReportText.renderMarkdown(section -> completion.textOf(section)),
                marketCutoffOf(context).orElse(null),
                now));
        return messageId;
    }

    private AiReport composeReport(
            AiTask task,
            LlmCompletion completion,
            AiContextBuildResult context,
            long reportId,
            Long assistantMessageId,
            OffsetDateTime now) {
        List<String> limitations = context.limitations();
        AiReportQuality quality = limitations.isEmpty() ? AiReportQuality.VALID : AiReportQuality.LIMITED;
        String limitedReason = limitations.isEmpty() ? null : String.join("；", limitations);
        String coreConclusion = nullToEmpty(completion.textOf(AiReportSection.CORE_CONCLUSION));
        String quoteEvidence = nullToEmpty(completion.textOf(AiReportSection.QUOTE_EVIDENCE));
        String comparison = blankToNull(completion.textOf(AiReportSection.COMPARISON_ANALYSIS));
        String eventClues = blankToNull(completion.textOf(AiReportSection.EVENT_CLUES));
        String risk = nullToEmpty(completion.textOf(AiReportSection.RISK_AND_UNCERTAINTY));
        String disclaimer = nullToEmpty(completion.textOf(AiReportSection.DISCLAIMER));

        return new AiReport(
                reportId,
                task.taskId(),
                task.sessionId(),
                assistantMessageId,
                coreConclusion,
                quoteEvidence,
                comparison,
                eventClues,
                risk,
                disclaimer,
                AiReportText.renderMarkdown(section -> completion.textOf(section)),
                quality,
                limitedReason,
                contentSchemaVersion,
                promptVersion,
                task.providerCode(),
                task.modelCode(),
                // NOT NULL：核心行情齐备才走到这里，所以一定有行情类截止时间。
                // 缺了就是上游判据错了，宁可在这里炸掉也不要编一个"现在"。
                marketCutoffOf(context).orElseThrow(() -> new IllegalStateException(
                        "报告缺少行情数据截止时间：taskId=" + task.taskId())),
                newsCutoffOf(context).orElse(null),
                contentHashOf(completion),
                now);
    }

    /** 六章节文本的确定性哈希：同一份内容两次生成得到同一个值。 */
    private String contentHashOf(LlmCompletion completion) {
        StringBuilder source = new StringBuilder();
        for (AiReportSection section : AiReportSection.inOrder()) {
            source.append(section.name()).append('\u0000')
                    .append(nullToEmpty(completion.textOf(section))).append('\u0000');
        }
        return hasher.hashOfText(source.toString());
    }

    /**
     * 把这次分析固化的证据候选落成 {@code ai_evidence} 行（HIS-07）。
     *
     * <p>时机在报告写入**之后**、任务状态推进**之前**：证据是定稿的一部分，
     * 与报告同生。放在报告之后是因为 {@code ai_evidence.report_id} 是外键，
     * 而 {@code reportId} 此刻才拿到。
     *
     * <h2>为什么不在这里做去重或"补充"</h2>
     * 候选集合就是这次分析的全部事实来源，报告正文里出现的引用编号也只落在这个集合内
     * （{@code finishCompleted} 之前的校验已经保证）。所以这里只做<b>搬运</b>：
     * 候选有几条就写几行，不挑不拣。任何"补一条"的动作都会让证据表与正文引用对不上号。
     *
     * <h2>幂等</h2>
     * 由 {@code uk_ai_report_no} 兜底。恢复扫描若把已定稿的任务再跑一遍，会先在
     * {@code ai_report} 的唯一索引上失败，走不到这里；万一走到了，这里的唯一索引
     * 会让它显式报错而不是静默翻倍——这是想要的。
     */
    private void persistEvidence(AiReport report, AiContextBuildResult context, OffsetDateTime now) {
        List<AiEvidenceCandidate> candidates = context.evidenceCandidates();
        if (candidates.isEmpty()) {
            // 没有候选就是没有来源：写不出一条证据行，也不该编一条。
            return;
        }
        // 显式排序而不是"相信上游是升序的"：AiEvidenceStore 的写入契约要求升序，
        // 而这条要求不该由一个没有测试守着的前提来满足。排序让 ID 分配也变得确定性。
        List<AiEvidenceCandidate> ordered =
                candidates.stream().sorted(Comparator.comparingInt(AiEvidenceCandidate::evidenceNo)).toList();
        List<AiEvidence> rows = new ArrayList<>(ordered.size());
        for (AiEvidenceCandidate candidate : ordered) {
            rows.add(AiEvidence.fromCandidate(
                    idGenerator.getAsLong(), report.reportId(), candidate, now));
        }
        evidences.insertAll(rows);
    }

    // ---------- 终态 ----------

    private Outcome finishFailed(
            AiTask task, LlmErrorCategory category, String code, String message) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AiTask failed = task.failed(category, code, message, now);
        if (tasks.save(failed).isEmpty()) {
            return Outcome.SKIPPED;
        }
        emitError(task.taskId(), AiTaskStatus.FAILED, code, message, category.retryable());
        return Outcome.FAILED;
    }

    private Outcome finishCanceled(AiTask task, OffsetDateTime now) {
        AiTask canceled = task.canceled(now);
        if (tasks.save(canceled).isEmpty()) {
            return Outcome.SKIPPED;
        }
        events.append(
                task.taskId(),
                AiTaskEventType.STATUS,
                sequence -> AiTaskEventPayloads.status(
                        task.taskId(), AiTaskStatus.CANCELED, sequence));
        events.append(
                task.taskId(),
                AiTaskEventType.DONE,
                sequence -> AiTaskEventPayloads.done(
                        task.taskId(), AiTaskStatus.CANCELED, sequence));
        return Outcome.CANCELED;
    }

    private Outcome finishTimedOut(AiTask task, OffsetDateTime now) {
        AiTask timedOut = task.timedOut(now);
        if (tasks.save(timedOut).isEmpty()) {
            return Outcome.SKIPPED;
        }
        emitError(
                task.taskId(),
                AiTaskStatus.TIMED_OUT,
                "AI_TASK_TIMED_OUT",
                "任务超过截止时间仍未完成",
                false);
        return Outcome.TIMED_OUT;
    }

    /** 检查点：取消意图与截止时间。返回非空表示应当中止。 */
    private Outcome stopIfInterrupted(AiTask task) {
        if (task.cancelRequested()) {
            return finishCanceled(task, OffsetDateTime.now(clock));
        }
        if (task.pastDeadline(OffsetDateTime.now(clock))) {
            return finishTimedOut(task, OffsetDateTime.now(clock));
        }
        return null;
    }

    // ---------- 小工具 ----------

    private boolean claimable(AiTaskStatus status) {
        return status == AiTaskStatus.CREATED || status == AiTaskStatus.QUEUED;
    }

    private AiTask reload(long taskId) {
        return tasks.find(taskId).orElseThrow(() ->
                new IllegalStateException("执行中任务消失：taskId=" + taskId));
    }

    private Optional<AiTask> advance(AiTask task, AiTaskStatus next) {
        AiTask updated = task.advance(next, OffsetDateTime.now(clock));
        Optional<AiTask> saved = tasks.save(updated);
        if (saved.isEmpty()) {
            LOGGER.warn("状态推进被乐观锁拒绝（任务可能已被别人接管）：taskId={}", task.taskId());
            return Optional.empty();
        }
        // 用**写入后**的那一份继续：数据库已经推进了 version，继续用手上那份会写不进去。
        emitStatus(saved.get(), next);
        return saved;
    }

    private void emitStatus(AiTask task, AiTaskStatus status) {
        events.append(
                task.taskId(),
                AiTaskEventType.STATUS,
                sequence -> AiTaskEventPayloads.status(task.taskId(), status, sequence));
    }

    /**
     * 失败类终态的事件对：{@code error} + {@code done}。
     *
     * <p>{@code finalStatus} 必须由调用方给出：早先这里写死了 {@code FAILED}，
     * 于是超时任务的 {@code done} 事件报的是 {@code FAILED}——任务表里是 {@code TIMED_OUT}，
     * 流里是 {@code FAILED}，前端与库里的结论不一致（而两边各自看都对）。
     */
    private void emitError(
            long taskId, AiTaskStatus finalStatus, String errorCode, String message, boolean retryable) {
        events.append(
                taskId,
                AiTaskEventType.ERROR,
                sequence -> AiTaskEventPayloads.error(taskId, errorCode, message, retryable, sequence));
        events.append(
                taskId,
                AiTaskEventType.DONE,
                sequence -> AiTaskEventPayloads.done(taskId, finalStatus, sequence));
    }

    private LlmRequest requestOf(AiTask task, AiContextBuildResult context) {
        List<LlmEvidence> evidence = new ArrayList<>();
        for (AiEvidenceCandidate candidate : context.evidenceCandidates()) {
            evidence.add(candidate.toLlmEvidence());
        }
        return new LlmRequest(
                task.providerCode(),
                task.modelCode(),
                promptVersion,
                contentSchemaVersion,
                AiPromptRenderer.systemPrompt(promptVersion, contentSchemaVersion),
                AiPromptRenderer.userPrompt(task, context),
                evidence,
                maxOutputTokens);
    }

    private static Optional<OffsetDateTime> marketCutoffOf(AiContextBuildResult context) {
        return earliestCutoff(context, AiContextType::marketData);
    }

    private static Optional<OffsetDateTime> newsCutoffOf(AiContextBuildResult context) {
        return earliestCutoff(context, category -> category == AiContextType.NEWS);
    }

    /**
     * 该类别组里最早的截止时间。
     *
     * <p>{@code AiContextBuildResult.dataCutoffs()} 已经按类别取了最早值，这里只是把
     * 「行情类」与「资讯类」两个组各自再取一次最早——"报告基于的数据不晚于 X"因此成立。
     *
     * <p>用显式谓词而不是两个布尔互相推导：后者写成 {@code marketData != x || isNews == x}
     * 这种形式时，读的人（包括三个月后的自己）无法一眼判断它到底选了哪几类。
     */
    private static Optional<OffsetDateTime> earliestCutoff(
            AiContextBuildResult context, Predicate<AiContextType> category) {
        OffsetDateTime earliest = null;
        for (AiDataCutoff cutoff : context.dataCutoffs()) {
            if (!category.test(cutoff.category())) {
                continue;
            }
            if (earliest == null || cutoff.dataCutoffAt().isBefore(earliest)) {
                earliest = cutoff.dataCutoffAt();
            }
        }
        return Optional.ofNullable(earliest);
    }

    private static String reasonOf(AiContextBuildResult context) {
        return context.limitations().isEmpty()
                ? "核心行情快照缺失"
                : String.join("；", context.limitations());
    }

    private static String describe(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 可变游标：片段到达时要推进 {@code first_chunk_at} / {@code heartbeat_at}，而 lambda 不能重绑定局部变量。 */
    private static final class Cursor {
        private AiTask task;

        Cursor(AiTask task) {
            this.task = task;
        }
    }
}
