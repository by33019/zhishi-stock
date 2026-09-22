package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSessionSummary;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * 会话历史列表的一项（契约 §HIS-01）。
 *
 * <h2>{@code isFavorite} 必须显式对齐</h2>
 * 契约的字段名是 {@code isFavorite}，而 Java 访问器是 {@code favorite()}。
 * record 的 JSON 名取自**组件名**，不像普通 bean 那样会剥掉 {@code isXxx()} 的 {@code is} 前缀——
 * 不加这一行，响应里就是 {@code favorite}，前端按契约取值会永远拿到 {@code undefined}。
 * 同 HIS-06 的 {@code isLimited}（那里已经被契约测试抓到过一次）。
 *
 * @param lastTask 最近任务；从未跑过任务时为 {@code null}（而不是一个空对象——
 *     后者会让前端把"还没有任务"渲染成"任务状态未知"）
 */
public record AiSessionSummaryView(
        String sessionId,
        AiScene scene,
        String title,
        String status,
        @JsonProperty("isFavorite") boolean favorite,
        LastTaskView lastTask,
        OffsetDateTime lastActivityAt,
        OffsetDateTime createdAt,
        int version) {

    /**
     * 最近任务的精简视图。
     *
     * <p>列表里只给"是哪个任务、到什么状态了"——完整的任务摘要（目标、进度、错误）
     * 属 HIS-04 的 `GET /ai/tasks/{taskId}`，列表页不需要，塞进来会让一页 20 条
     * 多带 20 份嵌套对象。
     */
    public record LastTaskView(String taskId, String status) {
    }

    public static AiSessionSummaryView from(AiSessionSummary summary) {
        return new AiSessionSummaryView(
                Long.toString(summary.sessionId()),
                summary.scene(),
                summary.title(),
                summary.status(),
                summary.favorite(),
                summary.lastTaskId() == null
                        ? null
                        : new LastTaskView(
                                Long.toString(summary.lastTaskId()), summary.lastTaskStatus()),
                summary.lastActivityAt(),
                summary.createdAt(),
                summary.version());
    }
}
