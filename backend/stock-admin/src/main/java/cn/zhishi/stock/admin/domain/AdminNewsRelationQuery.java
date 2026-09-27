package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.time.OffsetDateTime;

/**
 * 资讯关联的查询条件（契约 §17.2 ADM-NEWS-05）。
 *
 * <h2>{@code relationStatus} 缺省是 CANDIDATE，在用例层补</h2>
 * 契约原文"默认查看 CANDIDATE，供低置信关联人工复核"。缺省值补在
 * {@code AdminNewsRelationService} 而不是这里：record 的紧凑构造器拿不到
 * "这是不是调用方显式传的"，而 null 在这里表示"还没决定"，不是"CANDIDATE"。
 *
 * <p>时间范围与操作日志同一套口径（缺省 7 天、上限 90 天），由用例层用注入的
 * {@code Clock} 解析后经 {@link #withRange} 传回；范围筛选的是关联的
 * {@code created_at}（候选关系是采集时产生的，按产生时间翻旧账）。
 */
public record AdminNewsRelationQuery(
        NewsRelationStatus relationStatus,
        NewsTargetType targetType,
        Long newsId,
        java.math.BigDecimal minConfidence,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int page,
        int size) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_RANGE_DAYS = 7;
    public static final int MAX_RANGE_DAYS = 90;

    public AdminNewsRelationQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** 用已解析的时间范围换一份新条件；其余字段原样保留。 */
    public AdminNewsRelationQuery withRange(OffsetDateTime start, OffsetDateTime end) {
        return new AdminNewsRelationQuery(
                relationStatus, targetType, newsId, minConfidence,
                start, end, page, size);
    }
}
