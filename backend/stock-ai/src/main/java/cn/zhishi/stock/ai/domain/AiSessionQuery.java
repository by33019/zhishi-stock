package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 会话历史列表的查询条件（契约 §HIS-01 的 Query 参数）。
 *
 * <h2>为什么条件对象在 domain 而不在 web</h2>
 * 它决定的是"哪些会话属于这次查询"，而不是"HTTP 参数怎么解析"。放在领域层，
 * 仓储端口就能直接接收它，不必在应用层把每个字段拆开再传五个参数——
 * 后者在新增一个筛选条件时要改三处签名。
 *
 * <p>每个字段都可空，空表示"不筛选"。{@code keyword} 由用例层裁剪与校验长度，
 * 领域对象只承载已校验的值。
 *
 * @param scene    场景码，空表示不限
 * @param keyword  标题关键字，空表示不限
 * @param favorite 收藏筛选；{@code null} 表示不限，{@code true}/{@code false} 为精确匹配
 * @param startAt  最后活动时间下界（含），空表示不限
 * @param endAt    最后活动时间上界（含），空表示不限
 */
public record AiSessionQuery(
        AiScene scene,
        String keyword,
        Boolean favorite,
        OffsetDateTime startAt,
        OffsetDateTime endAt) {

    public static AiSessionQuery unfiltered() {
        return new AiSessionQuery(null, null, null, null, null);
    }
}
