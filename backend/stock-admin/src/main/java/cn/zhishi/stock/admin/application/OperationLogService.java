package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.OperationLogDetail;
import cn.zhishi.stock.admin.domain.OperationLogEntry;
import cn.zhishi.stock.admin.domain.OperationLogQuery;
import cn.zhishi.stock.admin.domain.OperationLogStore;
import cn.zhishi.stock.common.api.PageData;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 操作日志查询（契约 §16.2 LOG-01/02）。
 *
 * <h2>只读用例也要有用例层</h2>
 * 这一层的职责是把"接口参数"变成"一条能直接执行的查询"：补时间缺省、拦住超限跨度、
 * 读详情时补脱敏。这三件事都不该落在控制器（那里拿不到 {@code Clock}，
 * 也不该知道 90 天这个数字）或仓储（那里只认已解析的条件）。
 *
 * <h2>为什么缺省是"最近 7 天"而不是"全部"</h2>
 * {@code sys_log} 只增不减，且 {@code create_time} 有索引。不给缺省范围时，
 * 一次"打开日志页"就会变成对整表的一次扫描——而且是在运维最着急的时候发生。
 * 想看得更早，显式传 {@code startedAt}，上限 90 天。
 */
public class OperationLogService {

    private final OperationLogStore store;
    private final SensitiveParamsRedactor redactor;
    private final Clock clock;

    public OperationLogService(
            OperationLogStore store, SensitiveParamsRedactor redactor, Clock clock) {
        this.store = store;
        this.redactor = redactor;
        this.clock = clock;
    }

    public PageData<OperationLogEntry> list(OperationLogQuery query) {
        OperationLogQuery resolved = withResolvedRange(query);
        long total = store.count(resolved);
        // 总数为 0 时不必再取一页：空页与"查询一次没有结果"是同一个答案，
        // 而这次查询本身可能是全表里最贵的一次（宽时间范围 + 无命中索引）。
        List<OperationLogEntry> items = total == 0 ? List.of() : store.page(resolved);
        return PageData.of(items, resolved.page(), resolved.size(), total);
    }

    /**
     * 日志详情。脱敏在这一处发生，且**只在返回给调用方之前**发生——
     * 让仓储返回已脱敏的对象，就把"什么算敏感"这个业务判断压进了 SQL 映射。
     */
    public OperationLogDetail detail(long logId) {
        return store.find(logId)
                .map(detail -> detail.withParamsSummary(redactor.redact(detail.paramsSummary())))
                .orElseThrow(() -> AdminException.logNotFound(logId));
    }

    private OperationLogQuery withResolvedRange(OperationLogQuery query) {
        OffsetDateTime end =
                query.endedAt() == null ? OffsetDateTime.now(clock) : query.endedAt();
        OffsetDateTime start = query.startedAt() == null
                ? end.minusDays(OperationLogQuery.DEFAULT_RANGE_DAYS)
                : query.startedAt();

        if (end.isBefore(start)) {
            throw AdminException.invalidRequest("结束时间不能早于开始时间");
        }
        if (Duration.between(start, end).toDays() > OperationLogQuery.MAX_RANGE_DAYS) {
            throw AdminException.logRangeTooWide(
                    OperationLogQuery.MAX_RANGE_DAYS, OperationLogQuery.DEFAULT_RANGE_DAYS);
        }
        return query.withRange(start, end);
    }
}
