package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code ai_task} 及其伴生表的后台只读 SQL（契约 §19 ADM-AI-02 / ADM-AI-03）。
 *
 * <h2>正文列不出现在任何 SELECT 里</h2>
 * {@code ai_message} / {@code ai_report} 的正文列（{@code rendered_markdown}、
 * 消息内容）不是这里不查、而是查不到——本接口没有引用它们的语句，
 * "后台读不到正文"由 SQL 面本身保证，而不是靠调用方自律。
 *
 * <h2>一页任务的目标用第二条 IN 查询取回</h2>
 * 目标（{@code ai_task_target}）是任务的 1:N。JOIN 会让主行按目标数翻倍，
 * 分页就要去重再补齐——不如两条 SQL：先取任务页，再按页内 id 批量取目标，
 * 在存储层组装。
 *
 * <h2>排序带 {@code id} 兜底</h2>
 * 与 {@code OperationLogMapper} 同一条理由：翻页顺序必须确定。
 */
@Mapper
public interface AdminAiTaskMapper {

    String COLUMNS = """
            t.id               AS taskId,
            t.session_id       AS sessionId,
            t.user_id          AS userId,
            t.scene            AS scene,
            t.status           AS status,
            t.provider_code    AS providerCode,
            t.model_code       AS modelCode,
            t.created_at       AS createdAt,
            t.started_at       AS startedAt,
            t.completed_at     AS completedAt,
            t.error_category   AS errorCategory,
            t.trace_id         AS traceId
            """;

    /**
     * 过滤条件的参数是**扁平**的（与 {@code OperationLogMapper} 同一形态）。
     * OGNL 读不了嵌套 record 的属性（{@code query.taskId} 没有 getter），
     * 嵌套对象参数会在运行时炸成 MyBatisSystemException——这是 e2e 在真容器里
     * 抓出来的 500，单测与契约测试（stub 服务）都测不到 SQL 参数绑定。
     */
    String FILTER = """
            <where>
              <if test="taskId != null">
                AND t.id = #{taskId}
              </if>
              <if test="userId != null">
                AND t.user_id = #{userId}
              </if>
              <if test="scene != null and scene != ''">
                AND t.scene = #{scene}
              </if>
              <if test="status != null">
                AND t.status = #{status}
              </if>
              <if test="providerCode != null and providerCode != ''">
                AND t.provider_code = #{providerCode}
              </if>
              <if test="errorCategory != null">
                AND t.error_category = #{errorCategory}
              </if>
              AND t.created_at &gt;= #{startedAt}
              AND t.created_at &lt;= #{endedAt}
            </where>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM ai_task t" + FILTER
            + " ORDER BY t.created_at DESC, t.id DESC"
            + " LIMIT #{limit} OFFSET #{offset}</script>")
    List<TaskRow> pageRows(
            @Param("taskId") Long taskId,
            @Param("userId") Long userId,
            @Param("scene") String scene,
            @Param("status") AiTaskStatus status,
            @Param("providerCode") String providerCode,
            @Param("errorCategory") LlmErrorCategory errorCategory,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("endedAt") LocalDateTime endedAt,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM ai_task t" + FILTER + "</script>")
    long countRows(
            @Param("taskId") Long taskId,
            @Param("userId") Long userId,
            @Param("scene") String scene,
            @Param("status") AiTaskStatus status,
            @Param("providerCode") String providerCode,
            @Param("errorCategory") LlmErrorCategory errorCategory,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("endedAt") LocalDateTime endedAt);

    @Select("SELECT " + COLUMNS + " FROM ai_task t WHERE t.id = #{taskId}")
    TaskRow find(@Param("taskId") long taskId);

    /** 列集合必须与 {@code TaskExtras} 的分量**一一同名同序**：多一列都会让自动构造映射错位。 */
    @Select("""
            SELECT retry_of_task_id AS retryOfTaskId,
                   attempt_no  AS attemptNo,
                   max_attempts AS maxAttempts,
                   cancel_requested AS cancelRequested,
                   queued_at   AS queuedAt,
                   first_chunk_at AS firstChunkAt,
                   validating_at AS validatingAt,
                   deadline_at AS deadlineAt,
                   error_code  AS errorCode,
                   error_message AS errorMessage
            FROM ai_task WHERE id = #{taskId}
            """)
    TaskExtras findExtras(@Param("taskId") long taskId);

    /** 动态标签（foreach）必须显式包 `<script>`，否则 `#{id}` 会被当成顶层参数解析。 */
    @Select("""
            <script>
            SELECT task_id     AS taskId,
                   target_type AS targetType,
                   target_code AS targetCode,
                   target_name AS targetName,
                   target_role AS targetRole
            FROM ai_task_target
            WHERE task_id IN
            <foreach collection="taskIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            ORDER BY task_id, sort_no
            </script>
            """)
    List<TargetRow> targetsOfTasks(@Param("taskIds") List<Long> taskIds);

    @Select("""
            SELECT t.attempt_no         AS attemptNo,
                   t.result_status      AS resultStatus,
                   t.provider_code      AS providerCode,
                   t.model_code         AS modelCode,
                   t.prompt_tokens      AS promptTokens,
                   t.completion_tokens  AS completionTokens,
                   t.cached_tokens      AS cachedTokens,
                   t.total_tokens       AS totalTokens,
                   t.estimated_cost     AS estimatedCost,
                   t.first_chunk_latency_ms AS firstChunkLatencyMillis,
                   t.total_latency_ms   AS totalLatencyMillis
            FROM ai_usage t
            WHERE t.task_id = #{taskId}
            ORDER BY t.attempt_no
            """)
    List<UsageAttemptRow> usageOfTask(@Param("taskId") long taskId);

    @Select("""
            SELECT context_type AS contextType, COUNT(*) AS count
            FROM ai_context_snapshot
            WHERE task_id = #{taskId}
            GROUP BY context_type
            """)
    List<ContextTypeCountRow> contextTypeCounts(@Param("taskId") long taskId);

    // ---------- Mapper 私有的行形状 ----------

    /** {@code ai_task} 一行（列表与详情共用的元数据列）。 */
    record TaskRow(
            long taskId,
            long sessionId,
            long userId,
            String scene,
            AiTaskStatus status,
            String providerCode,
            String modelCode,
            LocalDateTime createdAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LlmErrorCategory errorCategory,
            String traceId) {
    }

    /** 详情页才需要的补充列。 */
    record TaskExtras(
            Long retryOfTaskId,
            int attemptNo,
            int maxAttempts,
            boolean cancelRequested,
            LocalDateTime queuedAt,
            LocalDateTime firstChunkAt,
            LocalDateTime validatingAt,
            LocalDateTime deadlineAt,
            String errorCode,
            String errorMessage) {
    }

    /** 目标快照行。 */
    record TargetRow(
            long taskId,
            String targetType,
            String targetCode,
            String targetName,
            String targetRole) {
    }

    /** 单次调用的台账行。 */
    record UsageAttemptRow(
            int attemptNo,
            String resultStatus,
            String providerCode,
            String modelCode,
            long promptTokens,
            long completionTokens,
            long cachedTokens,
            long totalTokens,
            BigDecimal estimatedCost,
            Long firstChunkLatencyMillis,
            Long totalLatencyMillis) {
    }

    /** 上下文类型计数行。 */
    record ContextTypeCountRow(String contextType, long count) {
    }
}
