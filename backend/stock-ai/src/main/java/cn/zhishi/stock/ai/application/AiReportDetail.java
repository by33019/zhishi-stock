package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiReport;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * 结构化最终报告（契约 §HIS-06），HIS-06 的响应体。
 *
 * <h2>六章节平铺，且与 {@code renderedMarkdown} 同时返回</h2>
 * 两者不是重复：六列是**事实**，{@code renderedMarkdown} 是它们的渲染结果，
 * 渲染顺序由 {@code AiReportSection.inOrder()} 唯一决定。前端按章节渲染、
 * 导出按 Markdown 渲染，两条路径都需要，只给其中一个会逼调用方自己拼另一半
 * ——而自己拼的那份顺序必然与后端的分叉，且分叉不会报错。
 *
 * <h2>ID 一律是字符串</h2>
 * 同 {@code AiTaskSummary}：项目约定「业务 ID 用 Snowflake 字符串，前端全程 string」，
 * 因此在**类型上**就是 {@code String}，而不是靠序列化注解转换。JS 的 number
 * 装不下 19 位整数，靠注解的写法会让"某个响应漏了转换"变成只有前端才发现的缺陷。
 *
 * @param qualityStatus         {@code VALID} / {@code LIMITED}
 * @param limitedReason         仅 {@code LIMITED} 时非空（V6 的 CHECK 保证两者自洽）
 * @param marketDataCutoffAt    行情数据截止时刻。必填——报告必须能回答"数据到哪一刻"
 * @param newsDataCutoffAt      资讯数据截止时刻，无资讯时为 {@code null}
 * @param contentSchemaVersion  输出结构版本，与 {@code promptVersion} 一起让历史报告可解释
 * @param feedback              当前用户对这份报告的反馈；未评价时为 {@code null}
 */
public record AiReportDetail(
        String reportId,
        String taskId,
        String sessionId,
        String coreConclusion,
        String quoteEvidence,
        String comparisonAnalysis,
        String eventClues,
        String riskAndUncertainty,
        String disclaimer,
        String renderedMarkdown,
        String qualityStatus,
        /*
         * 契约 §HIS-06 的字段名是 isLimited，而 Java 侧的访问器是 limited()。
         * 必须显式对齐：record 的 JSON 名取自**组件名**，不像普通 bean 那样会把
         * isXxx() 的 is 前缀剥掉——不加这一行，响应里就是 limited，
         * 而前端按契约取 isLimited 会永远拿到 undefined。
         * 同 M2-01 的 MarketStatus 对 isTradingDay 的处置。
         */
        @JsonProperty("isLimited") boolean limited,
        String limitedReason,
        OffsetDateTime marketDataCutoffAt,
        OffsetDateTime newsDataCutoffAt,
        String contentSchemaVersion,
        String promptVersion,
        String providerCode,
        String modelCode,
        OffsetDateTime generatedAt,
        AiFeedbackView feedback) {

    /**
     * 由聚合投影。
     *
     * <p>{@code feedback} 由调用方传入而不是这里去查：查询反馈需要按
     * {@code (reportId, userId)} 取，而 {@code AiReport} 里没有 {@code userId}
     * （报告属于谁由任务表决定）。把那次查询藏进投影方法，会让"投影"这个名字
     * 掩盖一次数据库访问。
     *
     * <p>未评价时是 {@code null} 而不是一个 {@code feedbackType=NONE} 的占位：
     * 前者让前端能区分"还没有人评价"与"评价结果是中性"，而这两者对用户的意义不同。
     */
    public static AiReportDetail from(AiReport report, AiFeedbackView feedback) {
        return new AiReportDetail(
                Long.toString(report.reportId()),
                Long.toString(report.taskId()),
                Long.toString(report.sessionId()),
                report.coreConclusion(),
                report.quoteEvidence(),
                report.comparisonAnalysis(),
                report.eventClues(),
                report.riskAndUncertainty(),
                report.disclaimer(),
                report.renderedMarkdown(),
                report.qualityStatus(),
                report.limited(),
                report.limitedReason(),
                report.marketDataCutoffAt(),
                report.newsDataCutoffAt(),
                report.contentSchemaVersion(),
                report.promptVersion(),
                report.providerCode(),
                report.modelCode(),
                report.generatedAt(),
                feedback);
    }
}
