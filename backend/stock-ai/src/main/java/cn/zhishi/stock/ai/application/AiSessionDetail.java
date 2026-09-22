package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSession;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话详情（契约 §HIS-02），HIS-02 的响应体。
 *
 * <h2>{@code targets} 用 {@link AiContextTarget} 而不是自定义的精简类型</h2>
 * 与 {@link AiTaskSummary} 保持同一形状：同一个"目标"在会话详情与任务摘要里
 * 是同一份事实，分成两个类型会让前端为同一个渲染逻辑写两套。
 *
 * <h2>为什么要 {@code targetHydrator}</h2>
 * 从库里读回来的目标只有 bigint 代理键（{@code ai_task_target} 不存 {@code sim-600519}
 * 那种对外标识）。不还原就直出，前端拿到的跳转主键解析不了——M3-07 的集成测试踩过
 * "每个任务都以 AI_CONTEXT_BUILD_FAILED 失败"的同一个坑。
 *
 * @param lastTask   最近任务摘要；从未跑过任务时为 {@code null}
 * @param lastReport 最近任务的报告摘要；任务未产出报告（失败/超时/运行中）时为 {@code null}
 */
public record AiSessionDetail(
        String sessionId,
        AiScene scene,
        String title,
        String status,
        /*
         * 契约 §HIS-02 的字段名是 isFavorite。record 的 JSON 名取自**组件名**，
         * 不像普通 bean 那样剥掉 isXxx() 的 is 前缀——不显式对齐的话前端取不到。
         * 同 AiSessionSummaryView 与 HIS-06 的 isLimited。
         */
        @JsonProperty("isFavorite") boolean favorite,
        List<AiContextTarget> targets,
        AiTaskSummary lastTask,
        ReportBrief lastReport,
        OffsetDateTime lastActivityAt,
        OffsetDateTime createdAt,
        int version) {

    public AiSessionDetail {
        targets = List.copyOf(targets);
    }

    /**
     * 报告摘要。
     *
     * <p>刻意只给"是哪份、质量如何、何时生成"——完整报告属 HIS-06
     * （{@code GET /ai/reports/{reportId}}）。把六章节正文塞进会话详情，
     * 会让一个列表页的每次点击都拖回几百字 Markdown。
     */
    public record ReportBrief(
            String reportId,
            String qualityStatus,
            @JsonProperty("isLimited") boolean limited,
            OffsetDateTime generatedAt) {

        public static ReportBrief from(AiReport report) {
            return new ReportBrief(
                    Long.toString(report.reportId()),
                    report.qualityStatus(),
                    report.limited(),
                    report.generatedAt());
        }
    }

    public static AiSessionDetail of(
            AiSession session,
            List<AiContextTarget> targets,
            AiTaskSummary lastTask,
            ReportBrief lastReport) {
        return new AiSessionDetail(
                Long.toString(session.sessionId()),
                session.scene(),
                session.title(),
                session.status(),
                session.favorite(),
                targets,
                lastTask,
                lastReport,
                session.lastActivityAt(),
                session.createdAt(),
                session.version());
    }
}
