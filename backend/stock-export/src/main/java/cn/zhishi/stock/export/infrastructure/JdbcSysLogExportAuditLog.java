package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.domain.ExportAuditEvent;
import cn.zhishi.stock.export.domain.ExportAuditLog;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 导出审计写 {@code sys_log}（契约 §9.2 末段 + §22.3）。
 *
 * <h2>为什么审计失败只记日志、不抛异常</h2>
 * 契约要求写审计，但没有哪一条要求"日志表不可用时拒绝导出"。
 * 审计是**旁路事实**：它挂了，导出仍然应该成功；反过来，
 * 若把写日志的异常抛给用户，一次日志表的抖动就会表现成"导出功能整体不可用"。
 * 这与认证、幂等键的处置不同——那两者的失败会改变业务事实本身。
 *
 * <h2>两列刻意留空</h2>
 * {@code sys_log} 的 {@code method}（控制层方法）与 {@code time}（响应耗时）是旧结构留下的列。
 * 导出审计事件里没有这两项事实，因此写 NULL 而不是拿 {@code request_uri} 或操作名去填充：
 * 填一个语义相近但不同的值，会让"这个字段到底表示什么"在几个月后无从判断。
 * 需要它们时应由 Web 层统一埋点（过滤器/拦截器），而不是在这一处编造。
 */
public class JdbcSysLogExportAuditLog implements ExportAuditLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSysLogExportAuditLog.class);

    private static final String INSERT = """
            INSERT INTO sys_log (id, user_id, username, operation, method, request_uri, http_method,
                                 result_status, params, ip, trace_id, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public JdbcSysLogExportAuditLog(JdbcTemplate jdbc, LongSupplier idGenerator, Clock clock) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public void record(ExportAuditEvent event) {
        try {
            jdbc.update(
                    INSERT,
                    idGenerator.getAsLong(),
                    event.userId(),
                    event.username(),
                    event.operation(),
                    null,
                    event.requestUri(),
                    event.httpMethod(),
                    event.resultStatus(),
                    event.paramsSummary(),
                    event.ip(),
                    event.traceId(),
                    Timestamp.from(OffsetDateTime.now(clock).toInstant()));
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "导出审计写库失败：userId={}，operation={}，traceId={}",
                    event.userId(),
                    event.operation(),
                    event.traceId(),
                    exception);
        }
    }
}
