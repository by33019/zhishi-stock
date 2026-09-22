package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 会话历史列表的一行（契约 §HIS-01 的 item）。
 *
 * <h2>为什么单独一个类型，而不是复用 {@link AiSession}</h2>
 * 列表要多带一个 {@code lastTaskStatus}——它来自 join {@code ai_task}，
 * 不在 {@code ai_session} 行里。把它塞进 {@link AiSession} 会让"创建会话"时
 * 也要提供一个当时并不存在的任务状态（只能是 null），于是那个字段的含义
 * 在两条路径上不同：创建时是"无意义"，读列表时是"最近任务的状态"。
 *
 * <h2>{@code lastTaskStatus} 为什么必须来自 join</h2>
 * 逐行去查最近任务的状态是一个 N+1：一页 20 条就是 20 次查询。列表页刷新频繁，
 * 而它本可以在同一条 SQL 里 LEFT JOIN 出来。
 *
 * @param lastTaskId     最近任务 ID，从未跑过任务时为 {@code null}
 * @param lastTaskStatus 最近任务的状态码；{@code lastTaskId} 为空或任务行已不存在时为 {@code null}
 */
public record AiSessionSummary(
        long sessionId,
        long userId,
        AiScene scene,
        String title,
        String status,
        boolean favorite,
        Long lastTaskId,
        String lastTaskStatus,
        OffsetDateTime lastActivityAt,
        OffsetDateTime createdAt,
        int version) {

    /** 是否仍可追问（契约 §HIS-08 要求活动会话）。 */
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
