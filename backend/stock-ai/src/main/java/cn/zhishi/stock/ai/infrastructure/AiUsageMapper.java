package cn.zhishi.stock.ai.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiUsageMapper {

    String COLUMNS = """
            id                     AS usageId,
            task_id                AS taskId,
            attempt_no             AS attemptNo,
            user_id                AS userId,
            provider_code          AS providerCode,
            model_code             AS modelCode,
            provider_request_id    AS providerRequestId,
            prompt_tokens          AS promptTokens,
            completion_tokens      AS completionTokens,
            cached_tokens          AS cachedTokens,
            total_tokens           AS totalTokens,
            estimated_cost         AS estimatedCost,
            currency_code          AS currencyCode,
            first_chunk_latency_ms AS firstChunkLatencyMs,
            total_latency_ms       AS totalLatencyMs,
            result_status          AS resultStatus,
            error_category         AS errorCategory,
            call_started_at        AS callStartedAt,
            call_completed_at      AS callCompletedAt,
            created_at             AS createdAt
            """;

    /**
     * 追加一行。
     *
     * <p>刻意**不做** upsert：{@code uk_ai_usage_task_attempt} 撞键说明同一执行次数
     * 发起了两次调用，那是执行路径出了问题，应当报错而不是把第二笔开销覆盖掉
     * 已经记下的第一笔。
     */
    @Insert("""
            INSERT INTO ai_usage
              (id, task_id, attempt_no, user_id, provider_code, model_code, provider_request_id,
               prompt_tokens, completion_tokens, cached_tokens, total_tokens,
               estimated_cost, currency_code, first_chunk_latency_ms, total_latency_ms,
               result_status, error_category, call_started_at, call_completed_at, created_at)
            VALUES
              (#{usageId}, #{taskId}, #{attemptNo}, #{userId}, #{providerCode}, #{modelCode},
               #{providerRequestId}, #{promptTokens}, #{completionTokens}, #{cachedTokens},
               #{totalTokens}, #{estimatedCost}, #{currencyCode}, #{firstChunkLatencyMs},
               #{totalLatencyMs}, #{resultStatus}, #{errorCategory}, #{callStartedAt},
               #{callCompletedAt}, #{createdAt})
            """)
    void insert(AiUsageRow row);

    @Select("SELECT " + COLUMNS + " FROM ai_usage"
            + " WHERE task_id = #{taskId} ORDER BY attempt_no ASC")
    List<AiUsageRow> listByTask(@Param("taskId") long taskId);
}
