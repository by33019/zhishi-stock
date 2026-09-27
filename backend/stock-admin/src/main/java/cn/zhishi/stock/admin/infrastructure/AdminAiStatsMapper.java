package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.ai.domain.AiFeedbackType;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.admin.domain.AdminAiStatsStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI 运营统计的只读 SQL（契约 §19 ADM-AI-01 / 05 / 06）。
 *
 * <h2>时间永远是上海时区的本地时间</h2>
 * 写入侧统一以 {@code Clock}（Asia/Shanghai）把 {@code OffsetDateTime} 落成
 * {@code datetime(3)}，因此这里按自然日分组（{@code DATE_FORMAT(... '%Y-%m-%d')})
 * 的结果与运维在页面上理解的"一天"一致，不需要再做时区换算。
 *
 * <h2>反馈统计的三张表联结</h2>
 * {@code ai_feedback} 没有场景列，scene 维度经
 * {@code ai_feedback → ai_report → ai_task} 联结取得。联结条件是主键等值，
 * 数据量以"用户点过的反馈"为上界，不会成为慢查询。
 */
@Mapper
public interface AdminAiStatsMapper {

    @Select("""
            <script>SELECT status AS status, COUNT(*) AS count
            FROM ai_task t
            <where>
              t.created_at &gt;= #{start} AND t.created_at &lt;= #{end}
            </where>
            GROUP BY status</script>
            """)
    List<StatusCountRow> taskStatusCounts(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            SELECT COUNT(*)
            FROM ai_report r
            WHERE r.is_limited = 1 AND r.generated_at >= #{start} AND r.generated_at <= #{end}
            """)
    long restrictedReportCount(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT COUNT(*) FROM ai_task WHERE status = 'QUEUED'")
    long queuedCount();

    @Select("""
            SELECT COUNT(*) FROM ai_task
            WHERE status IN ('PREPARING', 'RUNNING', 'VALIDATING')
            """)
    long runningCount();

    @Select("""
            SELECT result_status       AS resultStatus,
                   prompt_tokens       AS promptTokens,
                   completion_tokens   AS completionTokens,
                   cached_tokens       AS cachedTokens,
                   total_tokens        AS totalTokens,
                   estimated_cost      AS estimatedCost,
                   first_chunk_latency_ms AS firstChunkLatencyMillis,
                   total_latency_ms    AS totalLatencyMillis
            FROM ai_usage
            WHERE call_started_at >= #{start} AND call_started_at <= #{end}
            """)
    List<AdminAiStatsStore.UsageAttemptRow> usageAttempts(
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            <script>SELECT
              <choose>
                <when test="groupBy.name() == 'DAY'">DATE_FORMAT(u.call_started_at, '%Y-%m-%d')</when>
                <when test="groupBy.name() == 'PROVIDER'">u.provider_code</when>
                <when test="groupBy.name() == 'MODEL'">u.model_code</when>
                <otherwise>t.scene</otherwise>
              </choose>
              AS groupKey,
              COUNT(*) AS calls,
              SUM(CASE WHEN u.result_status = 'SUCCESS' THEN 1 ELSE 0 END) AS successCalls,
              SUM(u.prompt_tokens)     AS promptTokens,
              SUM(u.completion_tokens) AS completionTokens,
              SUM(u.cached_tokens)     AS cachedTokens,
              SUM(u.total_tokens)      AS totalTokens,
              SUM(u.estimated_cost)    AS estimatedCost,
              AVG(u.first_chunk_latency_ms) AS avgFirstChunkLatencyMillis,
              AVG(u.total_latency_ms)  AS avgTotalLatencyMillis
            FROM ai_usage u
            JOIN ai_task t ON t.id = u.task_id
            <where>
              u.call_started_at &gt;= #{start} AND u.call_started_at &lt;= #{end}
              <if test="providerCode != null and providerCode != ''">AND providerCode = #{providerCode}</if>
              <if test="modelCode != null and modelCode != ''">AND modelCode = #{modelCode}</if>
              <if test="resultStatus != null and resultStatus != ''">AND resultStatus = #{resultStatus}</if>
            </where>
            GROUP BY
            <choose>
              <when test="groupBy.name() == 'DAY'">DATE_FORMAT(u.call_started_at, '%Y-%m-%d')</when>
              <when test="groupBy.name() == 'PROVIDER'">u.provider_code</when>
              <when test="groupBy.name() == 'MODEL'">u.model_code</when>
              <otherwise>t.scene</otherwise>
            </choose>
            ORDER BY groupKey
            </script>
            """)
    List<AdminAiStatsStore.UsageGroupRow> usageGroups(
            @Param("groupBy") AdminAiStatsStore.GroupBy groupBy,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("providerCode") String providerCode,
            @Param("modelCode") String modelCode,
            @Param("resultStatus") String resultStatus);

    @Select("""
            <script>SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN f.feedback_type = 'HELPFUL' THEN 1 ELSE 0 END) AS helpfulCount
            FROM ai_feedback f
            JOIN ai_report r ON r.id = f.report_id
            JOIN ai_task t ON t.id = r.task_id
            <where>
              f.created_at &gt;= #{start} AND f.created_at &lt;= #{end}
              <if test="scene != null">AND t.scene = #{scene}</if>
              <if test="feedbackType != null">AND f.feedback_type = #{feedbackType}</if>
              <if test="reasonCode != null">AND f.reason_code = #{reasonCode}</if>
            </where></script>
            """)
    TotalRow feedbackTotal(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("scene") String scene,
            @Param("feedbackType") AiFeedbackType feedbackType,
            @Param("reasonCode") String reasonCode);

    @Select("""
            <script>SELECT f.reason_code AS reasonCode, COUNT(*) AS count
            FROM ai_feedback f
            JOIN ai_report r ON r.id = f.report_id
            JOIN ai_task t ON t.id = r.task_id
            <where>
              f.created_at &gt;= #{start} AND f.created_at &lt;= #{end}
              <if test="scene != null">AND t.scene = #{scene}</if>
              <if test="feedbackType != null">AND f.feedback_type = #{feedbackType}</if>
              <if test="reasonCode != null">AND f.reason_code = #{reasonCode}</if>
            </where>
            GROUP BY f.reason_code
            ORDER BY count DESC</script>
            """)
    List<AdminAiStatsStore.ReasonCountRow> feedbackReasonCounts(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("scene") String scene,
            @Param("feedbackType") AiFeedbackType feedbackType,
            @Param("reasonCode") String reasonCode);

    @Select("""
            <script>SELECT
              DATE_FORMAT(f.created_at, '%Y-%m-%d') AS day, COUNT(*) AS count
            FROM ai_feedback f
            JOIN ai_report r ON r.id = f.report_id
            JOIN ai_task t ON t.id = r.task_id
            <where>
              f.created_at &gt;= #{start} AND f.created_at &lt;= #{end}
              <if test="scene != null">AND t.scene = #{scene}</if>
              <if test="feedbackType != null">AND f.feedback_type = #{feedbackType}</if>
              <if test="reasonCode != null">AND f.reason_code = #{reasonCode}</if>
            </where>
            GROUP BY DATE_FORMAT(f.created_at, '%Y-%m-%d')
            ORDER BY day</script>
            """)
    List<AdminAiStatsStore.DailyCountRow> feedbackDailyTrend(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("scene") String scene,
            @Param("feedbackType") AiFeedbackType feedbackType,
            @Param("reasonCode") String reasonCode);

    /** 状态计数行。 */
    record StatusCountRow(AiTaskStatus status, long count) {
    }

    /** 反馈总数行。 */
    record TotalRow(Long total, Long helpfulCount) {
    }
}
