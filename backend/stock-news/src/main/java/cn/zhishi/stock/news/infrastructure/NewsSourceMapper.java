package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code news_source} 的 SQL。
 *
 * <p>枚举列直接映射成 Java 枚举：MyBatis 默认的 {@code EnumTypeHandler} 按 {@code name()} 转换，
 * 而 V4 的 CHECK 约束取值集合与枚举名逐字相同，两者不会分叉。
 *
 * <p>健康状态的更新用 {@code CASE WHEN} 而不是直接赋值：{@code DISABLED} 是人工决定，
 * 一次采集成功不该把它改回 {@code ACTIVE}——那会让"停用"在下一次定时任务后失效。
 */
@Mapper
public interface NewsSourceMapper {

    String COLUMNS = """
            id                   AS sourceId,
            source_code          AS sourceCode,
            source_name          AS sourceName,
            source_type          AS sourceType,
            homepage_url         AS homepageUrl,
            authorization_status AS authorizationStatus,
            rights_valid_from    AS rightsValidFrom,
            rights_valid_to      AS rightsValidTo,
            allow_ai_analysis    AS allowAiAnalysis,
            status               AS status,
            last_success_at      AS lastSuccessAt,
            last_failure_at      AS lastFailureAt,
            version              AS version
            """;

    @Select("SELECT " + COLUMNS + " FROM news_source ORDER BY source_code")
    List<NewsSourceRow> findAll();

    @Select("SELECT " + COLUMNS + " FROM news_source WHERE source_code = #{sourceCode}")
    NewsSourceRow findByCode(@Param("sourceCode") String sourceCode);

    @Select("SELECT " + COLUMNS + " FROM news_source WHERE id = #{sourceId}")
    NewsSourceRow findById(@Param("sourceId") long sourceId);

    /** {@code created_at} / {@code updated_at} 交给列默认值，避免应用侧做时区换算。 */
    @Insert("""
            INSERT INTO news_source
              (id, source_code, source_name, source_type, homepage_url,
               authorization_status, rights_valid_from, rights_valid_to,
               allow_ai_analysis, status, version)
            VALUES
              (#{sourceId}, #{sourceCode}, #{sourceName}, #{sourceType}, #{homepageUrl},
               #{authorizationStatus}, #{rightsValidFrom}, #{rightsValidTo},
               #{allowAiAnalysis}, #{status}, 0)
            """)
    void insert(
            @Param("sourceId") long sourceId,
            @Param("sourceCode") String sourceCode,
            @Param("sourceName") String sourceName,
            @Param("sourceType") NewsSourceType sourceType,
            @Param("homepageUrl") String homepageUrl,
            @Param("authorizationStatus") NewsSource.AuthorizationStatus authorizationStatus,
            @Param("rightsValidFrom") LocalDate rightsValidFrom,
            @Param("rightsValidTo") LocalDate rightsValidTo,
            @Param("allowAiAnalysis") boolean allowAiAnalysis,
            @Param("status") NewsSource.SourceStatus status);

    @Update("""
            UPDATE news_source
               SET last_success_at = #{at},
                   status = CASE WHEN status = 'DISABLED' THEN 'DISABLED' ELSE 'ACTIVE' END,
                   version = version + 1
             WHERE id = #{sourceId}
            """)
    int markSuccess(@Param("sourceId") long sourceId, @Param("at") LocalDateTime at);

    @Update("""
            UPDATE news_source
               SET last_failure_at = #{at},
                   status = CASE WHEN status = 'DISABLED' THEN 'DISABLED' ELSE 'DEGRADED' END,
                   version = version + 1
             WHERE id = #{sourceId}
            """)
    int markFailure(@Param("sourceId") long sourceId, @Param("at") LocalDateTime at);
}
