package cn.zhishi.stock.admin.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code sys_log} 的查询 SQL（契约 §16.2 LOG-01/02）。
 *
 * <h2>这是全项目唯一在读 {@code sys_log} 的地方</h2>
 * 写入侧只有 {@code JdbcSysLogAuditLog} 一处，读取侧只有这里一处。
 * 审计表的读写各有单一入口，"日志到底怎么进来、怎么出去"才可以被完整地回答。
 *
 * <h2>时间范围一定是已解析的</h2>
 * {@code startedAt} / {@code endedAt} 由 {@code OperationLogService} 补齐（缺省 7 天、上限 90 天）
 * 后传入。因此 {@link #FILTER} 里没有"如果为空就不过滤"这条退路——
 * 留了退路，一次调用方漏传就变成全表扫描，而且不会有任何报错。
 *
 * <h2>排序带 {@code id} 兜底</h2>
 * {@code create_time} 精度只到秒，同一秒内的多条日志（一次写操作会同时留下
 * 主记录与关联记录）在仅按时间排序时顺序不定，翻页会出现重复或漏项。
 * 加 {@code id DESC} 让顺序确定：id 由 Snowflake 单调递增生成。
 */
@Mapper
public interface OperationLogMapper {

    String COLUMNS = """
            l.id            AS logId,
            l.user_id       AS userId,
            l.legacy_user_ref AS legacyUserRef,
            l.username      AS username,
            l.operation     AS operation,
            l.time          AS durationMillis,
            l.method        AS method,
            l.request_uri   AS requestUri,
            l.http_method   AS httpMethod,
            l.result_status AS resultStatus,
            l.params        AS paramsSummary,
            l.ip            AS ip,
            l.trace_id      AS traceId,
            l.create_time   AS createdAt
            """;

    /** 分页与计数共用的过滤条件；条件之间是 AND，各自可空。 */
    String FILTER = """
            <where>
              <if test="userId != null">
                AND l.user_id = #{userId}
              </if>
              <if test="username != null and username != ''">
                AND l.username = #{username}
              </if>
              <if test="operation != null and operation != ''">
                AND l.operation = #{operation}
              </if>
              <if test="resultStatus != null and resultStatus != ''">
                AND l.result_status = #{resultStatus}
              </if>
              <if test="httpMethod != null and httpMethod != ''">
                AND l.http_method = #{httpMethod}
              </if>
              <if test="requestUri != null and requestUri != ''">
                AND l.request_uri = #{requestUri}
              </if>
              <if test="traceId != null and traceId != ''">
                AND l.trace_id = #{traceId}
              </if>
              <if test="ip != null and ip != ''">
                AND l.ip = #{ip}
              </if>
              AND l.create_time &gt;= #{startedAt}
              AND l.create_time &lt;= #{endedAt}
            </where>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM sys_log l" + FILTER
            + " ORDER BY l.create_time DESC, l.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<OperationLogRow> pageRows(
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("operation") String operation,
            @Param("resultStatus") String resultStatus,
            @Param("httpMethod") String httpMethod,
            @Param("requestUri") String requestUri,
            @Param("traceId") String traceId,
            @Param("ip") String ip,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("endedAt") LocalDateTime endedAt,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM sys_log l" + FILTER + "</script>")
    long countRows(
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("operation") String operation,
            @Param("resultStatus") String resultStatus,
            @Param("httpMethod") String httpMethod,
            @Param("requestUri") String requestUri,
            @Param("traceId") String traceId,
            @Param("ip") String ip,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("endedAt") LocalDateTime endedAt);

    /**
     * 单条日志。
     *
     * <p>参数摘要列是 {@code text}，可能很长；但这正是详情接口的用途，
     * 这里不做截断——截断属于展示层，读侧截断会让"摘要本来就短"与"被截了"分不清。
     */
    @Select("SELECT " + COLUMNS + " FROM sys_log l WHERE l.id = #{logId}")
    OperationLogRow find(@Param("logId") long logId);
}
