package cn.zhishi.stock.system.audit;

import cn.zhishi.stock.common.audit.AuditEvent;
import cn.zhishi.stock.common.audit.AuditLog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审计写 {@code sys_log}（契约 §9.2 末段 + §22.3 + §16）。
 *
 * <h2>为什么实现放在 stock-system</h2>
 * `sys_log` 是系统域的表（`sys_` 前缀，与 `sys_user` / `sys_role` 同一组），
 * 而 {@code stock-admin} 与 {@code stock-backend} 都依赖 {@code stock-system}。
 * 实现留在 {@code stock-export} 里的话，后台管理要写同一张表就得反向依赖导出域——
 * 那是把"日志表属于谁的"这个问题用依赖方向错误地回答了一遍。
 *
 * <h2>为什么审计失败只记日志、不抛异常</h2>
 * 契约要求写审计，但没有哪一条要求"日志表不可用时拒绝业务操作"。
 * 审计是**旁路事实**：它挂了，业务仍然应该成功；反过来，
 * 若把写日志的异常抛给用户，一次日志表的抖动就会表现成"整个功能不可用"。
 * 这与认证、幂等键的处置不同——那两者的失败会改变业务事实本身。
 *
 * <h2>两列刻意留空</h2>
 * {@code sys_log} 的 {@code method}（控制层方法）与 {@code time}（响应耗时）是旧结构留下的列。
 * 审计事件里没有这两项事实，因此写 NULL 而不是拿 {@code request_uri} 或操作名去填充：
 * 填一个语义相近但不同的值，会让"这个字段到底表示什么"在几个月后无从判断。
 * 需要它们时应由 Web 层统一埋点（过滤器/拦截器），而不是在这一处编造。
 *
 * <h2>写入时刻截断到秒</h2>
 * {@code create_time} 是精度为 0 的 {@code datetime}，而驱动对带小数的值做的是
 * <b>四舍五入</b>而不是截断。结果是一条日志的创建时间可以落在**写入时刻之后**最多半秒，
 * 于是"按 [某时刻, 现在] 查询"会把刚写下的那条漏掉——更早的记录都查得到，只有最新的那条不见，
 * 看起来像是写入失败。日志的时间精度只用于展示与筛选，秒级足够；
 * 截断之后 {@code create_time <= 实际写入时刻} 恒成立，
 * 且同一批写入的记录都落在同一秒内（批内的先后由 {@code id} 兜底，见 {@code OperationLogMapper}）。
 */
public class JdbcSysLogAuditLog implements AuditLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSysLogAuditLog.class);

    private static final String INSERT = """
            INSERT INTO sys_log (id, user_id, username, operation, method, request_uri, http_method,
                                 result_status, params, ip, trace_id, create_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public JdbcSysLogAuditLog(JdbcTemplate jdbc, LongSupplier idGenerator, Clock clock) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            // withNano(0)：见类注释"写入时刻截断到秒"。不截断的话，读侧用"现在"作上界
            // 会查不到刚刚写下的这条。
            //
            // 落库值用 Clock 时区的 LocalDateTime，而不是 Timestamp.from(instant)：
            // Timestamp 会按 JDBC 会话时区（URL 钉死 Asia/Shanghai）换算，而读侧
            // （MyBatisOperationLogStore）按 Clock 时区换算——两者只在"JVM 时区恰好也是
            // 上海"的机器上一致，CI 的 UTC JVM 上会错开 8 小时，窗口过滤整组失败。
            // 写读两侧都必须走同一个 Clock 时区，与 session 时区无关。
            OffsetDateTime recordedAt = OffsetDateTime.now(clock).withNano(0);
            LocalDateTime recordedAtLocal = recordedAt.atZoneSameInstant(clock.getZone()).toLocalDateTime();
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
                    recordedAtLocal);
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "审计写库失败：userId={}，operation={}，traceId={}",
                    event.userId(),
                    event.operation(),
                    event.traceId(),
                    exception);
        }
    }
}
