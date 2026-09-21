package cn.zhishi.stock.ai.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code ai_report} 的 SQL。
 *
 * <p>插入不吞唯一索引冲突：{@code uk_ai_report_task} 撞索引意味着
 * "同一个任务被执行了两遍"——正是乐观锁没兜住的情况。
 * 静默忽略会让"任务成功但没有报告"变成一个无法解释的状态。
 */
@Mapper
public interface AiReportMapper {

    String COLUMNS = """
            id                     AS reportId,
            task_id                AS taskId,
            session_id             AS sessionId,
            assistant_message_id   AS assistantMessageId,
            core_conclusion        AS coreConclusion,
            quote_evidence         AS quoteEvidence,
            comparison_analysis    AS comparisonAnalysis,
            event_clues            AS eventClues,
            risk_and_uncertainty   AS riskAndUncertainty,
            disclaimer             AS disclaimer,
            rendered_markdown      AS renderedMarkdown,
            quality_status         AS quality,
            limited_reason         AS limitedReason,
            content_schema_version AS contentSchemaVersion,
            prompt_version         AS promptVersion,
            provider_code          AS providerCode,
            model_code             AS modelCode,
            market_data_cutoff_at  AS marketDataCutoffAt,
            news_data_cutoff_at    AS newsDataCutoffAt,
            content_hash           AS contentHash,
            generated_at           AS generatedAt
            """;

    @Insert("""
            INSERT INTO ai_report
              (id, task_id, session_id, assistant_message_id, core_conclusion, quote_evidence,
               comparison_analysis, event_clues, risk_and_uncertainty, disclaimer,
               rendered_markdown, is_limited, limited_reason, quality_status,
               content_schema_version, prompt_version, provider_code, model_code,
               market_data_cutoff_at, news_data_cutoff_at, content_hash, generated_at)
            VALUES
              (#{reportId}, #{taskId}, #{sessionId}, #{assistantMessageId}, #{coreConclusion},
               #{quoteEvidence}, #{comparisonAnalysis}, #{eventClues}, #{riskAndUncertainty},
               #{disclaimer}, #{renderedMarkdown}, #{limited}, #{limitedReason}, #{quality},
               #{contentSchemaVersion}, #{promptVersion}, #{providerCode}, #{modelCode},
               #{marketDataCutoffAt}, #{newsDataCutoffAt}, #{contentHash}, #{generatedAt})
            """)
    void insert(AiReportRow row);

    @Select("SELECT " + COLUMNS + " FROM ai_report WHERE id = #{reportId}")
    AiReportRow find(@Param("reportId") long reportId);

    @Select("SELECT " + COLUMNS + " FROM ai_report WHERE task_id = #{taskId}")
    AiReportRow findByTask(@Param("taskId") long taskId);
}
