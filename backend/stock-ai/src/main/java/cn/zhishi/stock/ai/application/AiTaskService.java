package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextBuildResult;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSceneDefinition;
import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStatusMachine;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 任务编排用例：AI-03（创建）、AI-04（查询）、AI-06（取消）、AI-07（重试）、AI-08（追问）。
 *
 * <h2>创建的四道闸门，顺序固定</h2>
 * 校验与目标解析 → 并发上限 → 每日额度 → 核心行情。
 * 顺序本身是接口的一部分：同一份请求必须稳定地报同一条错误，
 * 否则前端提示会在两次提交之间跳动。
 *
 * <h2>幂等的两层</h2>
 * <ul>
 *   <li><b>第一层</b>在控制器：{@code IdempotencyGuard}（Redis）回放第一次的完整响应。
 *   <li><b>第二层</b>在这里：{@code ai_task.request_id} 唯一索引。
 *       它兜的是"Redis 里的幂等记录丢了"——此时不能报错，而要返回**已有任务**，
 *       因为契约 §3.7 要的是"重复创建不得重复消耗额度"，不是"重复创建必须失败"。
 * </ul>
 *
 * <p>{@code request_id} 由 {@code (userId, key)} 确定性派生，**不直接写客户端 key**：
 * {@code Idempotency-Key} 只在同一用户内唯一，两个用户传同一个 key 会撞上全局唯一索引，
 * 而正确行为是两个人都该成功。
 *
 * <h2>为什么创建时就要取一次上下文</h2>
 * 核心行情缺失必须**拒绝创建**（契约 §13.5），而"有没有核心行情"只有取数才知道。
 * 这一次取数的结果**被丢弃**——真正固化的那份由 Worker 在执行时构建并写进
 * {@code ai_context_snapshot}。两者之间数据若发生变化，以 Worker 那份为准；
 * 若 Worker 那份也缺核心行情，任务以 {@code AI_CORE_DATA_MISSING} 失败（同一判据的两处兑现）。
 */
public class AiTaskService {

    /** V6 的 {@code ai_task.max_attempts} 默认值：自动重试一次。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private static final String STATUS_URL_PREFIX = "/api/v1/ai/tasks/";
    private static final String STREAM_URL_SUFFIX = "/stream";

    private final AiTaskRequestResolver resolver;
    private final AiContextBuilder contextBuilder;
    private final AiTaskStore tasks;
    private final AiSessionStore sessions;
    private final AiMessageStore messages;
    private final AiReportStore reports;
    private final AiTaskQueue queue;
    private final SecurityIdentityProvider securities;
    private final SectorIdentityProvider sectors;
    private final LongSupplier idGenerator;
    private final Clock clock;
    private final int dailyTaskLimit;
    private final int maxConcurrentTasks;
    private final Duration taskDeadline;
    private final String providerCode;
    private final String modelCode;

    public AiTaskService(
            AiTaskRequestResolver resolver,
            AiContextBuilder contextBuilder,
            AiTaskStore tasks,
            AiSessionStore sessions,
            AiMessageStore messages,
            AiReportStore reports,
            AiTaskQueue queue,
            SecurityIdentityProvider securities,
            SectorIdentityProvider sectors,
            LongSupplier idGenerator,
            Clock clock,
            int dailyTaskLimit,
            int maxConcurrentTasks,
            Duration taskDeadline,
            String providerCode,
            String modelCode) {
        this.resolver = resolver;
        this.contextBuilder = contextBuilder;
        this.tasks = tasks;
        this.sessions = sessions;
        this.messages = messages;
        this.reports = reports;
        this.queue = queue;
        this.securities = securities;
        this.sectors = sectors;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.dailyTaskLimit = dailyTaskLimit;
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.taskDeadline = taskDeadline;
        this.providerCode = providerCode;
        this.modelCode = modelCode;
    }

    // ---------- AI-03 创建 ----------

    /**
     * 创建任务。
     *
     * @param idempotencyKey 契约要求的 {@code Idempotency-Key}（已由控制器校验非空）
     * @param traceId        贯穿日志与 {@code ai_task.trace_id}
     */
    @Transactional
    public AiTaskAccepted create(
            AiTaskCreationRequest request,
            long userId,
            String idempotencyKey,
            String traceId) {
        AiResolvedRequest resolved = resolver.resolve(
                request.scene(),
                request.targets(),
                request.analysisStartAt(),
                request.analysisEndAt());
        resolver.validateQuestion(resolved.definition(), request.question());

        AiSession session = sessionFor(request.sessionId(), userId, resolved, request.question());
        return submit(
                session.sessionId(),
                userId,
                resolved.scene(),
                resolved.targets(),
                resolved.analysisStartAt(),
                resolved.analysisEndAt(),
                request.question(),
                idempotencyKey,
                traceId,
                null);
    }

    // ---------- AI-04 查询 ----------

    public AiTaskSummary get(long taskId, long userId) {
        AiTask task = requireOwned(taskId, userId);
        return summaryOf(task);
    }

    /** 当日配额与并发占用（契约 USER-07 的字段集）。 */
    public AiTaskQuota quotaOf(long userId) {
        return quotaOf(userId, OffsetDateTime.now(clock));
    }

    // ---------- AI-06 取消 ----------

    /**
     * 请求取消。
     *
     * <p>终态任务**保持终态**并返回 {@code effectiveImmediately=false}（契约 AI-06 原文）：
     * 报告已经存在了，把 {@code COMPLETED} 改成 {@code CANCELED} 会让用户丢掉已经生成的内容。
     */
    @Transactional
    public AiCancelResult cancel(long taskId, long userId) {
        AiTask task = requireOwned(taskId, userId);
        if (!AiTaskStatusMachine.cancellable(task.status())) {
            return new AiCancelResult(
                    Long.toString(taskId), task.status(), task.cancelRequested(), false);
        }
        tasks.requestCancel(taskId);
        return new AiCancelResult(Long.toString(taskId), task.status(), true, true);
    }

    // ---------- AI-07 重试 ----------

    /**
     * 重试失败或超时的任务。
     *
     * <p>创建**新任务**并置 {@code retry_of_task_id}，不覆盖旧任务与旧用量：
     * 旧任务带着失败原因留在历史里，是排查的依据；覆盖它就等于把证据删掉。
     *
     * <p>目标复用原任务已固化的那份，不重新解析：重试的语义是"重跑同一次分析"，
     * 重新解析会让"某个标识恰好下线"变成一个与重试无关的新失败点。
     */
    @Transactional
    public AiTaskAccepted retry(
            long taskId, long userId, String idempotencyKey, String question, String traceId) {
        AiTask original = requireOwned(taskId, userId);
        if (!AiTaskStatusMachine.retryable(original.status())) {
            throw new AiTaskException(
                    AiTaskErrorCode.TASK_NOT_RETRYABLE,
                    "只有失败或超时的任务可以重试，当前状态：" + original.status());
        }
        AiSceneDefinition definition = resolver.definitionOf(original.scene().name());
        String effectiveQuestion = blankToNull(question) == null ? original.question() : question;
        resolver.validateQuestion(definition, effectiveQuestion);
        resolver.validateRange(
                original.analysisStartAt(), original.analysisEndAt(), definition);

        return submit(
                original.sessionId(),
                userId,
                original.scene(),
                original.targets(),
                original.analysisStartAt(),
                original.analysisEndAt(),
                effectiveQuestion,
                idempotencyKey,
                traceId,
                taskId);
    }

    // ---------- AI-08 追问 ----------

    /**
     * 在活动会话里追问。
     *
     * <p>架构 §8.3：复用必要历史，但**重新固化最新数据上下文**——
     * 所以这里创建的是一个全新的任务（会重新取数），而不是在旧任务上追加。
     * 场景与目标取自会话里最近一次任务：让客户端重传会让
     * "追问是不是换了分析对象"变成一个可以悄悄发生的事。
     */
    @Transactional
    public AiTaskAccepted followUp(
            long sessionId,
            long userId,
            AiFollowUpRequest request,
            String idempotencyKey,
            String traceId) {
        AiSession session = requireOwnedSession(sessionId, userId);
        if (!session.active()) {
            throw new AiTaskException(
                    AiTaskErrorCode.SESSION_READ_ONLY,
                    "会话已不是活动状态，无法追问：" + session.status());
        }
        if (blankToNull(request.question()) == null) {
            throw InvalidAiContextQueryException.invalid("追问必须提供 question");
        }
        AiTask last = session.lastTaskId() == null
                ? null
                : tasks.find(session.lastTaskId()).orElse(null);
        if (last == null) {
            throw InvalidAiContextQueryException.invalid("会话没有可追问的任务：" + sessionId);
        }

        AiSceneDefinition definition = resolver.definitionOf(session.scene().name());
        resolver.validateQuestion(definition, request.question());
        OffsetDateTime start = request.analysisStartAt() == null
                ? last.analysisStartAt()
                : request.analysisStartAt();
        OffsetDateTime end = request.analysisEndAt() == null
                ? last.analysisEndAt()
                : request.analysisEndAt();
        resolver.validateRange(start, end, definition);

        return submit(
                sessionId,
                userId,
                session.scene(),
                last.targets(),
                start,
                end,
                request.question(),
                idempotencyKey,
                traceId,
                null);
    }

    // ---------- 三个入口共用的闸门与落库 ----------

    private AiTaskAccepted submit(
            long sessionId,
            long userId,
            AiScene scene,
            List<AiContextTarget> targets,
            OffsetDateTime start,
            OffsetDateTime end,
            String question,
            String idempotencyKey,
            String traceId,
            Long retryOfTaskId) {
        guardConcurrency(userId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        guardQuota(userId, now);
        requireCoreData(targets, start, end);

        String requestId = requestIdOf(userId, idempotencyKey);
        Optional<AiTask> replayed = tasks.findByRequestId(requestId);
        if (replayed.isPresent()) {
            return accepted(replayed.get(), userId, now);
        }

        long taskId = idGenerator.getAsLong();
        String normalizedQuestion = blankToNull(question);
        AiTask task = new AiTask(
                taskId,
                requestId,
                sessionId,
                userId,
                retryOfTaskId,
                scene,
                normalizedQuestion,
                start,
                end,
                AiTaskStatus.QUEUED,
                false,
                0,
                DEFAULT_MAX_ATTEMPTS,
                providerCode,
                modelCode,
                traceId,
                now,
                now,
                null,
                null,
                null,
                null,
                now.plus(taskDeadline),
                null,
                null,
                null,
                null,
                0,
                targets);

        try {
            tasks.insert(task);
        } catch (DuplicateKeyException exception) {
            // 并发下另一个请求先写了同一个 request_id：返回它的任务，而不是报错。
            Optional<AiTask> concurrent = tasks.findByRequestId(requestId);
            if (concurrent.isEmpty()) {
                throw exception;
            }
            return accepted(concurrent.get(), userId, now);
        }

        writeQuestionMessage(sessionId, taskId, normalizedQuestion, now);
        sessions.touch(sessionId, taskId, now);
        // 投递失败不回滚任务：它已是 QUEUED，恢复扫描会重新投递。
        // 反过来（先投递后落库）会出现"Worker 拿到了一个库里还不存在的任务"。
        queue.enqueue(taskId);
        return accepted(task, userId, now);
    }

    private void guardConcurrency(long userId) {
        int active = tasks.countByUserAndStatuses(userId, AiTaskStatus.activeStatuses());
        if (active >= maxConcurrentTasks) {
            throw new AiTaskException(
                    AiTaskErrorCode.CONCURRENCY_EXCEEDED,
                    "单用户最多 " + maxConcurrentTasks + " 个进行中的 AI 任务，当前 " + active + " 个");
        }
    }

    private void guardQuota(long userId, OffsetDateTime now) {
        int used = tasks.countCreatedSince(userId, dayStart(now));
        if (used >= dailyTaskLimit) {
            throw new AiQuotaExceededException(quotaOf(userId, now));
        }
    }

    private void requireCoreData(
            List<AiContextTarget> targets, OffsetDateTime start, OffsetDateTime end) {
        AiContextBuildResult result = contextBuilder.build(targets, start, end);
        if (result.coreDataAvailable()) {
            return;
        }
        String reason = result.limitations().isEmpty()
                ? "核心行情快照缺失"
                : String.join("；", result.limitations());
        throw new AiTaskException(
                AiTaskErrorCode.CORE_DATA_MISSING, "核心行情暂不可用，无法创建分析任务：" + reason);
    }

    private AiTaskAccepted accepted(AiTask task, long userId, OffsetDateTime now) {
        String statusUrl = STATUS_URL_PREFIX + task.taskId();
        return new AiTaskAccepted(
                summaryOf(task),
                statusUrl,
                statusUrl + STREAM_URL_SUFFIX,
                quotaOf(userId, now));
    }

    /**
     * 摘要的**唯一**投影路径（AI-03 的响应与 AI-04 的查询都走这里）。
     *
     * <p>{@code reportId} 从 {@code ai_report.task_id} 解析，而不是读任务表里的某一列——
     * 任务表里没有这一列。刻意**不**按状态决定要不要查：
     * "报告已写入但任务状态推进失败"是一条真实存在的路径，此时按状态跳过查询
     * 会让一份确实存在的报告变成前端永远拿不到的孤儿。
     * 报告在不在，由报告表回答。
     */
    private AiTaskSummary summaryOf(AiTask task) {
        Long reportId = reports.findByTask(task.taskId()).map(AiReport::reportId).orElse(null);
        return AiTaskSummary.from(task, hydrate(task.targets()), reportId);
    }

    /**
     * 把库里的目标还原成带对外标识的形状。
     *
     * <p>{@code ai_task_target} 只存 bigint 代理键与代码 / 名称快照，**不存对外标识**
     * （{@code sim-600519}）。还原必须走 {@code *IdentityProvider.findByStorageIds}——
     * 在这里拼 {@code "sim-" + code} 就是第二份构词规则，而两份规则分歧不会报错，
     * 只会让前端拼出的跳转链接 404（M2-06 / M2-11 各踩过一次）。
     *
     * <p>市场目标的对外标识就是市场代码本身，库里那份快照已经够用，不需要查主数据。
     *
     * <p>主数据里查不到的（如已退市的证券）保留在列表里、对外标识留 {@code null}：
     * 契约要求降级不失败，而前端只在有标识时才渲染跳转链接。
     */
    private List<AiContextTarget> hydrate(List<AiContextTarget> targets) {
        Set<Long> securityIds = new LinkedHashSet<>();
        Set<Long> sectorIds = new LinkedHashSet<>();
        for (AiContextTarget target : targets) {
            if (target.storageId() == null) {
                continue;
            }
            switch (target.targetType()) {
                case SECURITY -> securityIds.add(target.storageId());
                case SECTOR -> sectorIds.add(target.storageId());
                case MARKET -> {
                    // 无需查询
                }
            }
        }
        Map<Long, SecurityIdentity> resolvedSecurities = securities.findByStorageIds(securityIds);
        Map<Long, SectorIdentity> resolvedSectors = sectors.findByStorageIds(sectorIds);

        List<AiContextTarget> hydrated = new ArrayList<>(targets.size());
        for (AiContextTarget target : targets) {
            hydrated.add(switch (target.targetType()) {
                case SECURITY -> {
                    SecurityIdentity identity = target.storageId() == null
                            ? null
                            : resolvedSecurities.get(target.storageId());
                    yield new AiContextTarget(
                            AiTargetType.SECURITY,
                            identity == null ? null : identity.securityId(),
                            target.targetCode(),
                            target.targetName(),
                            target.targetRole(),
                            target.storageId());
                }
                case SECTOR -> {
                    SectorIdentity identity = target.storageId() == null
                            ? null
                            : resolvedSectors.get(target.storageId());
                    yield new AiContextTarget(
                            AiTargetType.SECTOR,
                            identity == null ? null : identity.sectorId(),
                            target.targetCode(),
                            target.targetName(),
                            target.targetRole(),
                            target.storageId());
                }
                case MARKET -> new AiContextTarget(
                        AiTargetType.MARKET,
                        target.targetCode(),
                        target.targetCode(),
                        target.targetName(),
                        target.targetRole(),
                        target.storageId());
            });
        }
        return List.copyOf(hydrated);
    }

    private AiTaskQuota quotaOf(long userId, OffsetDateTime now) {
        int used = tasks.countCreatedSince(userId, dayStart(now));
        int running = tasks.countByUserAndStatuses(userId, AiTaskStatus.activeStatuses());
        return AiTaskQuota.of(
                dailyTaskLimit, used, running, maxConcurrentTasks, dayStart(now).plusDays(1));
    }

    // ---------- 会话 ----------

    private AiSession sessionFor(
            String rawSessionId, long userId, AiResolvedRequest resolved, String question) {
        if (blankToNull(rawSessionId) == null) {
            return createSession(userId, resolved, question);
        }
        long sessionId = parseId(rawSessionId, "sessionId");
        return requireOwnedSession(sessionId, userId);
    }

    private AiSession createSession(long userId, AiResolvedRequest resolved, String question) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        long sessionId = idGenerator.getAsLong();
        AiSession session = new AiSession(
                sessionId,
                userId,
                resolved.scene(),
                titleOf(resolved, question),
                "ACTIVE",
                false,
                null,
                now,
                0,
                now);
        sessions.insert(session);
        return session;
    }

    private AiSession requireOwnedSession(long sessionId, long userId) {
        AiSession session = sessions.find(sessionId)
                .orElseThrow(() -> AiTaskException.sessionNotFound(sessionId));
        if (session.userId() != userId) {
            // 与"不存在"同一句话：不能泄露他人会话的存在性（契约 §23.1）。
            throw AiTaskException.sessionNotFound(sessionId);
        }
        return session;
    }

    private AiTask requireOwned(long taskId, long userId) {
        AiTask task = tasks.find(taskId).orElseThrow(() -> AiTaskException.taskNotFound(taskId));
        if (task.userId() != userId) {
            throw AiTaskException.taskNotFound(taskId);
        }
        return task;
    }

    private void writeQuestionMessage(
            long sessionId, long taskId, String question, OffsetDateTime now) {
        if (question == null) {
            return;
        }
        messages.insert(new AiMessage(
                idGenerator.getAsLong(),
                sessionId,
                taskId,
                AiMessageRole.USER,
                messages.nextSequenceNo(sessionId),
                question,
                null,
                now));
    }

    // ---------- 小工具 ----------

    /**
     * 由 {@code (userId, key)} 确定性派生 {@code request_id}（v3 形状 UUID，36 字符）。
     *
     * <p>包级可见是为了让一致性测试能直接断言"同输入同输出、不同用户不同输出"，
     * 而不必绕道造两个任务再比库里的值。
     */
    static String requestIdOf(long userId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(
                        (userId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /** {@code Asia/Shanghai} 自然日零点。统计口径一律钉这个时区（CI 是 UTC）。 */
    private OffsetDateTime dayStart(OffsetDateTime now) {
        ZoneId zone = clock.getZone();
        return now.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
    }
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long parseId(String raw, String field) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException exception) {
            throw InvalidAiContextQueryException.invalid(field + " 不是合法的 ID：" + raw);
        }
    }

    private static String titleOf(AiResolvedRequest resolved, String question) {
        String trimmed = blankToNull(question);
        if (trimmed != null) {
            return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60);
        }
        String target = resolved.targets().isEmpty()
                ? ""
                : " · " + resolved.targets().get(0).targetName();
        return resolved.definition().name() + target;
    }
}
