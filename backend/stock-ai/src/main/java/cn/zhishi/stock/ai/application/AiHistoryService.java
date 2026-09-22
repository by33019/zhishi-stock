package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSession;
import cn.zhishi.stock.ai.domain.AiSessionQuery;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiSessionSummary;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.common.api.PageData;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 会话历史（契约 §HIS-01 / §HIS-05）。
 *
 * <h2>归属校验：三种"拿不到"共用同一个 404</h2>
 * 会话不存在、会话属于别人、会话已软删——三者返回同一句 {@code AI_SESSION_NOT_FOUND}。
 * 可区分它们就等于提供了探测他人会话 ID 是否有效的接口（契约 §23.1）。
 * 软删也按"不存在"处理，与 §HIS-02"删除状态对普通列表不可见"同一口径：
 * 列表里看不到、详情却拿得到，等于把删除做成了一扇后门。
 *
 * <h2>分页越界返回 400 而不是钳制</h2>
 * 与排行榜 / 证券列表的既有处置一致（契约对分页越界没有单独规定，项目统一按
 * {@code INVALID_REQUEST} 处理）。静默钳制会让"第 99 页"与"最后一页"返回同样的内容，
 * 而调用方无从知道自己的页码被改了。
 */
public class AiHistoryService {

    static final int DEFAULT_PAGE = 1;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_KEYWORD_LENGTH = 50;

    /** 软删状态码，与 {@code ai_session.status} 的取值域一致。 */
    private static final String STATUS_DELETED = "DELETED";

    private final AiSessionStore sessions;
    private final AiMessageStore messages;
    private final AiTaskStore tasks;
    private final AiReportStore reports;
    private final AiTargetHydrator targetHydrator;

    public AiHistoryService(
            AiSessionStore sessions,
            AiMessageStore messages,
            AiTaskStore tasks,
            AiReportStore reports,
            AiTargetHydrator targetHydrator) {
        this.sessions = sessions;
        this.messages = messages;
        this.tasks = tasks;
        this.reports = reports;
        this.targetHydrator = targetHydrator;
    }

    /** 本人会话历史（契约 §HIS-01），按最后活动时间倒序。 */
    public PageData<AiSessionSummaryView> listSessions(
            long userId,
            String sceneCode,
            String keyword,
            Boolean favorite,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            Integer page,
            Integer size) {
        int effectivePage = pageOf(page);
        int effectiveSize = sizeOf(size);
        AiSessionQuery query =
                new AiSessionQuery(sceneOf(sceneCode), keywordOf(keyword), favorite, startAt, endAt);

        long total = sessions.countByUser(userId, query);
        int totalPages = totalPagesOf(total, effectiveSize);
        requirePageWithinRange(effectivePage, totalPages);

        List<AiSessionSummary> rows = sessions.listByUser(
                userId, query, offsetOf(effectivePage, effectiveSize), effectiveSize);
        return new PageData<>(
                rows.stream().map(AiSessionSummaryView::from).toList(),
                effectivePage,
                effectiveSize,
                total,
                totalPages,
                effectivePage < totalPages);
    }

    /**
     * 会话详情（契约 §HIS-02）：会话摘要 + 目标摘要 + 最近任务与报告摘要。
     *
     * <h2>目标必须经 {@code targetHydrator} 还原</h2>
     * 从库里读回来的目标只有 bigint 代理键（{@code ai_task_target} 不存 {@code sim-600519}
     * 那种对外标识）。直出的话前端拿到的跳转主键解析不了，而页面只会显示"打不开"——
     * M3-07 的集成测试踩过同一个坑（那次的表现是每个任务都失败）。
     *
     * <h2>报告摘要允许缺失，且这不表示"没有报告"以外的任何意思</h2>
     * 任务失败 / 超时 / 仍在运行都不会产出报告，此时 {@code lastReport} 为 {@code null}。
     * 不去"猜"一个更友好的说法（比如把运行中显示成"报告生成中"）：那是把
     * {@code lastTask.status} 已经表达过的事实再说一遍，而两处说法必然分叉。
     */
    public AiSessionDetail getSession(long sessionId, long userId) {
        AiSession session = requireOwnedSession(sessionId, userId);
        if (session.lastTaskId() == null) {
            return AiSessionDetail.of(session, List.of(), null, null);
        }
        Optional<AiTask> found = tasks.find(session.lastTaskId());
        if (found.isEmpty()) {
            // last_task_id 指向的行已不在（数据不一致）：按"没有任务"处理而不是抛错。
            return AiSessionDetail.of(session, List.of(), null, null);
        }
        AiTask task = found.get();
        List<AiContextTarget> targets = targetHydrator.hydrate(task.targets());
        Optional<AiReport> report = reports.findByTask(task.taskId());
        return AiSessionDetail.of(
                session,
                targets,
                AiTaskSummary.from(task, targets, report.map(AiReport::reportId).orElse(null)),
                report.map(AiSessionDetail.ReportBrief::from).orElse(null));
    }

    /**
     * 会话消息（契约 §HIS-05），按 {@code sequenceNo} 升序。
     *
     * <p>{@code SYSTEM} 内部 Prompt 由查询层排除，因此这里的 {@code total}
     * 与实际返回的可见条数一致——两处用同一条 WHERE（见 {@code AiMessageMapper.VISIBLE_WHERE}），
     * 否则会出现"总数 20、这一页 17 条"而最后几页看起来少了东西。
     */
    public PageData<AiMessageView> messages(long sessionId, long userId, Integer page, Integer size) {
        requireOwnedSession(sessionId, userId);
        int effectivePage = pageOf(page);
        int effectiveSize = sizeOf(size);

        long total = messages.countVisible(sessionId);
        int totalPages = totalPagesOf(total, effectiveSize);
        requirePageWithinRange(effectivePage, totalPages);

        List<AiMessage> rows = messages.listVisible(
                sessionId, offsetOf(effectivePage, effectiveSize), effectiveSize);
        return new PageData<>(
                rows.stream().map(AiMessageView::from).toList(),
                effectivePage,
                effectiveSize,
                total,
                totalPages,
                effectivePage < totalPages);
    }

    private AiSession requireOwnedSession(long sessionId, long userId) {
        AiSession session = sessions.find(sessionId)
                .orElseThrow(() -> AiTaskException.sessionNotFound(sessionId));
        if (session.userId() != userId || STATUS_DELETED.equals(session.status())) {
            // 与"不存在"同一句话：不能泄露他人会话的存在性（契约 §23.1）。
            throw AiTaskException.sessionNotFound(sessionId);
        }
        return session;
    }

    private static int pageOf(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < 1) {
            throw new InvalidAiHistoryQueryException("页码从 1 开始：" + page);
        }
        return page;
    }

    private static int sizeOf(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            // 不静默钳到上限：调用方以为拿到了 500 条、实际 100 条时，
            // 它会据此算出错误的页数，而那个错误只在前端翻页时暴露。
            throw new InvalidAiHistoryQueryException(
                    "每页条数必须在 1 到 " + MAX_PAGE_SIZE + " 之间：" + size);
        }
        return size;
    }

    private static AiScene sceneOf(String sceneCode) {
        if (sceneCode == null || sceneCode.isBlank()) {
            return null;
        }
        String normalized = sceneCode.trim().toUpperCase(Locale.ROOT);
        for (AiScene scene : AiScene.values()) {
            if (scene.name().equals(normalized)) {
                return scene;
            }
        }
        throw new InvalidAiHistoryQueryException("不支持的场景码：" + sceneCode);
    }

    private static String keywordOf(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new InvalidAiHistoryQueryException(
                    "关键字不得超过 " + MAX_KEYWORD_LENGTH + " 字符：" + trimmed.length());
        }
        return trimmed;
    }

    private static int totalPagesOf(long total, int size) {
        return (int) ((total + size - 1) / size);
    }

    private static int offsetOf(int page, int size) {
        return (page - 1) * size;
    }

    private static void requirePageWithinRange(int page, int totalPages) {
        // 第 1 页永远允许（没有任何数据时它返回空页，这是正常状态而不是错误）。
        if (page > 1 && page > totalPages) {
            throw new InvalidAiHistoryQueryException(
                    "页码超出范围：" + page + "，共 " + totalPages + " 页");
        }
    }
}
