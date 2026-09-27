package cn.zhishi.stock.system.job;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code job_execution_summary} 的 SQL（契约 §16.3 ADM-JOB-01~05）。
 *
 * <h2>为什么参数逐个展开，而不是传一个 record</h2>
 * MyBatis 的属性访问依赖 {@code Reflector} 对 {@code getXxx()} 形式的识别，
 * 而 record 的访问器是 {@code xxx()}。能否解析取决于 MyBatis 版本，
 * 一旦不支持，症状是 {@code There is no getter for property named ...}——
 * 一条指向表达式而非字段的报错。逐个 {@code @Param} 展开没有这层不确定性，
 * 代价只是签名长一点。
 *
 * <h2>{@code complete} 带 {@code status = 'RUNNING'} 条件</h2>
 * 回填只应当发生一次。带上这个条件之后，重复回填、以及回填一条从未开始的记录，
 * 都会影响 0 行——调用方拿到 {@code false} 后记一条 warn，而不是让后面的数据悄悄覆盖前面的。
 *
 * <h2>分页与计数共用 {@link #FILTER}</h2>
 * 两处各写一遍时，漏掉其中一个条件会表现为"总数对、列表不对"（或反之），
 * 而那种不一致在页面上非常难看出来。
 */
@Mapper
public interface JobExecutionMapper {

    String COLUMNS = """
            id                   AS executionId,
            job_name             AS jobName,
            handler_name         AS handlerName,
            batch_id             AS batchId,
            provider_id          AS providerId,
            trigger_type         AS triggerType,
            status               AS status,
            shard_index          AS shardIndex,
            shard_total          AS shardTotal,
            attempt_no           AS attemptNo,
            scheduled_at         AS scheduledAt,
            started_at           AS startedAt,
            completed_at         AS completedAt,
            source_data_start_at AS sourceDataStartAt,
            source_data_end_at   AS sourceDataEndAt,
            input_count          AS inputCount,
            success_count        AS successCount,
            ignored_count        AS ignoredCount,
            failure_count        AS failureCount,
            output_count         AS outputCount,
            counts_available     AS countsAvailable,
            error_category       AS errorCategory,
            error_code           AS errorCode,
            error_summary        AS errorSummary,
            trace_id             AS traceId,
            created_at           AS createdAt,
            updated_at           AS updatedAt
            """;

    /** 分页与计数共用的过滤条件。时间条件由用例层保证非空（缺省 7 天、上限 90 天）。 */
    String FILTER = """
            <where>
              <if test="jobName != null and jobName != ''">
                AND job_name = #{jobName}
              </if>
              <if test="providerId != null">
                AND provider_id = #{providerId}
              </if>
              <if test="status != null and status != ''">
                AND status = #{status}
              </if>
              <if test="triggerType != null and triggerType != ''">
                AND trigger_type = #{triggerType}
              </if>
              <if test="batchId != null and batchId != ''">
                AND batch_id = #{batchId}
              </if>
              AND started_at &gt;= #{startedAt}
              AND started_at &lt;= #{endedAt}
            </where>
            """;

    @Insert("""
            INSERT INTO job_execution_summary
              (id, job_name, handler_name, batch_id, provider_id, trigger_type, status,
               shard_index, shard_total, attempt_no, scheduled_at, started_at, trace_id)
            VALUES
              (#{executionId}, #{jobName}, #{handlerName}, #{batchId}, #{providerId},
               #{triggerType}, 'RUNNING', #{shardIndex}, #{shardTotal}, #{attemptNo},
               #{scheduledAt}, #{startedAt}, #{traceId})
            """)
    void insert(
            @Param("executionId") long executionId,
            @Param("jobName") String jobName,
            @Param("handlerName") String handlerName,
            @Param("batchId") String batchId,
            @Param("providerId") Long providerId,
            @Param("triggerType") String triggerType,
            @Param("shardIndex") int shardIndex,
            @Param("shardTotal") int shardTotal,
            @Param("attemptNo") int attemptNo,
            @Param("scheduledAt") LocalDateTime scheduledAt,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("traceId") String traceId);

    @Update("""
            UPDATE job_execution_summary
               SET status = #{status},
                   counts_available = #{countsAvailable},
                   input_count = #{inputCount},
                   success_count = #{successCount},
                   ignored_count = #{ignoredCount},
                   failure_count = #{failureCount},
                   output_count = #{outputCount},
                   error_category = #{errorCategory},
                   error_code = #{errorCode},
                   error_summary = #{errorSummary},
                   completed_at = #{completedAt}
             WHERE id = #{executionId} AND status = 'RUNNING'
            """)
    int complete(
            @Param("executionId") long executionId,
            @Param("status") String status,
            @Param("countsAvailable") boolean countsAvailable,
            @Param("inputCount") long inputCount,
            @Param("successCount") long successCount,
            @Param("ignoredCount") long ignoredCount,
            @Param("failureCount") long failureCount,
            @Param("outputCount") long outputCount,
            @Param("errorCategory") String errorCategory,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("completedAt") LocalDateTime completedAt);

    @Select("<script>SELECT " + COLUMNS + " FROM job_execution_summary" + FILTER
            + " ORDER BY started_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<JobExecutionRow> pageRows(
            @Param("jobName") String jobName,
            @Param("providerId") Long providerId,
            @Param("status") String status,
            @Param("triggerType") String triggerType,
            @Param("batchId") String batchId,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("endedAt") LocalDateTime endedAt,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM job_execution_summary" + FILTER + "</script>")
    long countRows(
            @Param("jobName") String jobName,
            @Param("providerId") Long providerId,
            @Param("status") String status,
            @Param("triggerType") String triggerType,
            @Param("batchId") String batchId,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("endedAt") LocalDateTime endedAt);

    @Select("SELECT " + COLUMNS + " FROM job_execution_summary WHERE id = #{executionId}")
    JobExecutionRow find(@Param("executionId") long executionId);

    /** 同批同分片下的最大尝试号；没有任何记录时返回 {@code null}。 */
    @Select("""
            SELECT MAX(attempt_no) FROM job_execution_summary
             WHERE job_name = #{jobName} AND batch_id = #{batchId} AND shard_index = #{shardIndex}
            """)
    Integer maxAttemptNo(
            @Param("jobName") String jobName,
            @Param("batchId") String batchId,
            @Param("shardIndex") int shardIndex);
}
