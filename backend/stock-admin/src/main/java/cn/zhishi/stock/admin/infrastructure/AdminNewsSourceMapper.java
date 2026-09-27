package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code news_source} 的后台查询与写入 SQL（契约 §17.1 ADM-NEWS-01~04）。
 *
 * <h2>读取侧只有这里一处按条件分页</h2>
 * 资讯域的 {@code NewsSourceMapper} 只有 {@code findAll} / {@code findByCode}
 * （采集侧全量读）；按条件分页是后台的用例，SQL 放在后台的 Mapper 里，
 * 两边各改各的不会互相牵动。
 *
 * <h2>PATCH 语义用 {@code COALESCE} 表达</h2>
 * 与 {@code AdminUserMapper} 同一条做法："不传就不改"留在 SQL 一处，
 * 不在 Java 侧拼两套 UPDATE，也不依赖动态 {@code <set>} 的 OGNL 求值。
 *
 * <h2>排序带 {@code id} 兜底</h2>
 * 与 {@code OperationLogMapper} 同一条理由：翻页顺序必须确定。
 */
@Mapper
public interface AdminNewsSourceMapper {

    String COLUMNS = """
            s.id                     AS sourceId,
            s.provider_id            AS providerId,
            s.source_code            AS sourceCode,
            s.source_name            AS sourceName,
            s.source_type            AS sourceType,
            s.homepage_url           AS homepageUrl,
            s.authorization_status   AS authorizationStatus,
            s.rights_valid_from      AS rightsValidFrom,
            s.rights_valid_to        AS rightsValidTo,
            s.allow_ai_analysis      AS allowAiAnalysis,
            s.status                 AS status,
            s.last_success_at        AS lastSuccessAt,
            s.last_failure_at        AS lastFailureAt,
            s.version                AS version,
            s.created_at             AS createdAt,
            s.updated_at             AS updatedAt
            """;

    /** 分页与计数共用的过滤条件；条件之间是 AND，各自可空。 */
    String FILTER = """
            <where>
              <if test="providerId != null">
                AND s.provider_id = #{providerId}
              </if>
              <if test="sourceType != null">
                AND s.source_type = #{sourceType}
              </if>
              <if test="authorizationStatus != null">
                AND s.authorization_status = #{authorizationStatus}
              </if>
              <if test="status != null">
                AND s.status = #{status}
              </if>
            </where>
            """;

    @Select("<script>SELECT " + COLUMNS + " FROM news_source s" + FILTER
            + " ORDER BY s.source_code ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AdminNewsSourceRow> pageRows(
            @Param("providerId") Long providerId,
            @Param("sourceType") NewsSourceType sourceType,
            @Param("authorizationStatus") NewsSource.AuthorizationStatus authorizationStatus,
            @Param("status") NewsSource.SourceStatus status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM news_source s" + FILTER + "</script>")
    long countRows(
            @Param("providerId") Long providerId,
            @Param("sourceType") NewsSourceType sourceType,
            @Param("authorizationStatus") NewsSource.AuthorizationStatus authorizationStatus,
            @Param("status") NewsSource.SourceStatus status);

    @Select("SELECT " + COLUMNS + " FROM news_source s WHERE s.id = #{sourceId}")
    AdminNewsSourceRow find(@Param("sourceId") long sourceId);

    @Select("SELECT " + COLUMNS + " FROM news_source s WHERE s.source_code = #{sourceCode}")
    AdminNewsSourceRow findByCode(@Param("sourceCode") String sourceCode);

    @Insert("""
            INSERT INTO news_source
              (id, provider_id, source_code, source_name, source_type, homepage_url,
               authorization_status, rights_valid_from, rights_valid_to,
               allow_ai_analysis, status)
            VALUES
              (#{id}, #{providerId}, #{sourceCode}, #{sourceName}, #{sourceType}, #{homepageUrl},
               #{authorizationStatus}, #{rightsValidFrom}, #{rightsValidTo},
               #{allowAiAnalysis}, #{status})
            """)
    int insert(
            @Param("id") long id,
            @Param("providerId") Long providerId,
            @Param("sourceCode") String sourceCode,
            @Param("sourceName") String sourceName,
            @Param("sourceType") NewsSourceType sourceType,
            @Param("homepageUrl") String homepageUrl,
            @Param("authorizationStatus") NewsSource.AuthorizationStatus authorizationStatus,
            @Param("rightsValidFrom") LocalDate rightsValidFrom,
            @Param("rightsValidTo") LocalDate rightsValidTo,
            @Param("allowAiAnalysis") boolean allowAiAnalysis,
            @Param("status") NewsSource.SourceStatus status);

    /**
     * 乐观锁更新。可改字段的全集就是契约 ADM-NEWS-04 允许的五项；
     * {@code authorization_status} 无条件覆盖——用例层已把最终值算好，
     * 存储层不做二次推导。
     */
    @Update("""
            UPDATE news_source SET
              source_name          = COALESCE(#{sourceName}, source_name),
              homepage_url         = COALESCE(#{homepageUrl}, homepage_url),
              rights_valid_from    = COALESCE(#{rightsValidFrom}, rights_valid_from),
              rights_valid_to      = COALESCE(#{rightsValidTo}, rights_valid_to),
              allow_ai_analysis    = COALESCE(#{allowAiAnalysis}, allow_ai_analysis),
              status               = COALESCE(#{status}, status),
              authorization_status = #{authorizationStatus},
              version              = version + 1
            WHERE id = #{sourceId} AND version = #{expectedVersion}
            """)
    int updateByCas(
            @Param("sourceId") long sourceId,
            @Param("expectedVersion") int expectedVersion,
            @Param("sourceName") String sourceName,
            @Param("homepageUrl") String homepageUrl,
            @Param("rightsValidFrom") LocalDate rightsValidFrom,
            @Param("rightsValidTo") LocalDate rightsValidTo,
            @Param("allowAiAnalysis") Boolean allowAiAnalysis,
            @Param("status") NewsSource.SourceStatus status,
            @Param("authorizationStatus") NewsSource.AuthorizationStatus authorizationStatus);
}
