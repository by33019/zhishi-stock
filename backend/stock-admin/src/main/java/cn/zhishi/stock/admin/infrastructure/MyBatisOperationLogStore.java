package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.OperationLogDetail;
import cn.zhishi.stock.admin.domain.OperationLogEntry;
import cn.zhishi.stock.admin.domain.OperationLogQuery;
import cn.zhishi.stock.admin.domain.OperationLogStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link OperationLogStore} 的 MyBatis 实现。
 *
 * <h2>这一层没有脱敏</h2>
 * 刻意没有。仓储的返回值里有明文 {@code paramsSummary}，由 {@code OperationLogService}
 * 在返回给调用方之前替换掉。理由与 {@code AdminUserStore} 相反，但结论一致：
 * **脱敏只应有一处**。用户列表那边放在仓储，是因为数据库行里就有明文密码列，
 * 越早丢掉越好；日志这边放用例层，是因为脱敏规则（键名黑名单）是业务知识，
 * 而它在用例层已经有一个可单独测试的 {@code SensitiveParamsRedactor}。
 *
 * <p>两者共同的约束是：脱敏之后的值不再有明文版本流到更外层。
 *
 * <h2>空白过滤条件归一成 null</h2>
 * 前端把空输入框原样发上来会得到 {@code username=""}。若直接传给 SQL，
 * {@code <if test="username != null and username != ''">} 恰好能挡住空串，
 * 但挡不住 {@code " "}（一个空格）。在这里 trim 后转 null，条件判断就只剩一处。
 */
public class MyBatisOperationLogStore implements OperationLogStore {

    private final OperationLogMapper mapper;
    private final Clock clock;

    public MyBatisOperationLogStore(OperationLogMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<OperationLogEntry> page(OperationLogQuery query) {
        return mapper.pageRows(
                        query.userId(),
                        text(query.username()),
                        text(query.operation()),
                        text(query.resultStatus()),
                        text(query.httpMethod()),
                        text(query.requestUri()),
                        text(query.traceId()),
                        text(query.ip()),
                        toLocalDateTime(query.startedAt()),
                        toLocalDateTime(query.endedAt()),
                        query.size(),
                        query.offset())
                .stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public long count(OperationLogQuery query) {
        return mapper.countRows(
                query.userId(),
                text(query.username()),
                text(query.operation()),
                text(query.resultStatus()),
                text(query.httpMethod()),
                text(query.requestUri()),
                text(query.traceId()),
                text(query.ip()),
                toLocalDateTime(query.startedAt()),
                toLocalDateTime(query.endedAt()));
    }

    @Override
    public Optional<OperationLogDetail> find(long logId) {
        return Optional.ofNullable(mapper.find(logId)).map(this::toDetail);
    }

    private OperationLogEntry toEntry(OperationLogRow row) {
        return new OperationLogEntry(
                row.logId(),
                row.userId(),
                row.username(),
                row.operation(),
                row.durationMillis(),
                row.requestUri(),
                row.httpMethod(),
                row.resultStatus(),
                row.ip(),
                row.traceId(),
                toOffsetDateTime(row.createdAt()));
    }

    private OperationLogDetail toDetail(OperationLogRow row) {
        return new OperationLogDetail(
                row.logId(),
                row.userId(),
                row.legacyUserRef(),
                row.username(),
                row.operation(),
                row.durationMillis(),
                row.method(),
                row.requestUri(),
                row.httpMethod(),
                row.resultStatus(),
                row.paramsSummary(),
                row.ip(),
                row.traceId(),
                toOffsetDateTime(row.createdAt()));
    }

    private static String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
