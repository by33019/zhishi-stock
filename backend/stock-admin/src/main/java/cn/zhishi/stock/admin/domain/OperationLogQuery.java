package cn.zhishi.stock.admin.domain;

import java.time.OffsetDateTime;

/**
 * 操作日志的查询条件（契约 §16.2 LOG-01）。
 *
 * <h2>时间范围的三条规则都在这里</h2>
 * 缺省 7 天、上限 90 天（契约原文"默认最多查询 90 天范围"）。这两个数字放在领域层，
 * 是因为它们描述的是"这份日志能被怎么查"，而不是某个接口的参数解析细节。
 *
 * <h2>为什么缺省与上限不写在紧凑构造器里</h2>
 * 补缺省需要"现在几点"，而 record 的紧凑构造器拿不到 {@code Clock}；
 * 在这里读 {@code OffsetDateTime.now()} 会让这个类型没法被测（时间不可控）。
 * 因此构造器只做**与时间无关**的夹取（页码、每页条数），
 * 时间范围由 {@code OperationLogService} 用注入的 {@code Clock} 补齐，
 * 再经 {@link #withRange} 传回来。{@link #startedAt} / {@link #endedAt} 为 {@code null}
 * 只在此刻之前是合法状态。
 */
public record OperationLogQuery(
        Long userId,
        String username,
        String operation,
        String resultStatus,
        String httpMethod,
        String requestUri,
        String traceId,
        String ip,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int page,
        int size) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_RANGE_DAYS = 7;
    public static final int MAX_RANGE_DAYS = 90;

    public OperationLogQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** 用已解析的时间范围换一份新条件；其余字段原样保留。 */
    public OperationLogQuery withRange(OffsetDateTime start, OffsetDateTime end) {
        return new OperationLogQuery(
                userId, username, operation, resultStatus, httpMethod, requestUri, traceId, ip,
                start, end, page, size);
    }
}
