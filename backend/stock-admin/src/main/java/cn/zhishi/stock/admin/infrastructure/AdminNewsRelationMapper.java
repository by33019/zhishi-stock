package cn.zhishi.stock.admin.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code stock_news_relation} 的后台查询与写入 SQL（契约 §17.2 ADM-NEWS-05~08）。
 *
 * <h2>JOIN 只取标题一列</h2>
 * 契约要的"新闻摘要"就是标题（完整正文按授权规则本就不出库）。JOIN 放在
 * 这一处而不是用例层二次查询：分页后的 id 集合回表查标题要写第二批 SQL，
 * 而 MySQL 对每页 ≤100 行的 JOIN 成本可忽略。
 *
 * <h2>时间范围一定是已解析的</h2>
 * 与 {@code OperationLogMapper} 同一条约定：缺省与上限在
 * {@code AdminNewsRelationService} 补齐后才进来，{@code FILTER} 里没有
 * "为空就不过滤"的退路。
 *
 * <h2>没有乐观锁列</h2>
 * V4 的关联表没有 {@code version}；复核不是并发竞争面（先查
 * {@code reviewed_at IS NULL} 再写，契约语义下二次复核直接 409），
 * 因此这里用普通 UPDATE，靠用例层的状态检查保证"只审一次"。
 */
@Mapper
public interface AdminNewsRelationMapper {

    String COLUMNS = """
            r.id                AS relationId,
            r.news_id           AS newsId,
            n.title             AS newsTitle,
            r.target_type       AS targetType,
            r.target_id         AS targetId,
            r.relation_method   AS relationMethod,
            r.confidence_score  AS confidenceScore,
            r.relation_status   AS relationStatus,
            r.reason_summary    AS reasonSummary,
            r.reviewed_by       AS reviewedBy,
            r.reviewed_at       AS reviewedAt,
            r.created_at        AS createdAt
            """;

    String FROM = """
            FROM stock_news_relation r
            JOIN stock_news n ON n.id = r.news_id
            """;

    /** 分页与计数共用的过滤条件；时间范围由用例层保证非空。 */
    String FILTER = """
            <where>
              r.relation_status = #{relationStatus}
              <if test="targetType != null">
                AND r.target_type = #{targetType}
              </if>
              <if test="newsId != null">
                AND r.news_id = #{newsId}
              </if>
              <if test="minConfidence != null">
                AND r.confidence_score &gt;= #{minConfidence}
              </if>
              AND r.created_at &gt;= #{createdStartAt}
              AND r.created_at &lt;= #{createdEndAt}
            </where>
            """;

    @Select("<script>SELECT " + COLUMNS + FROM + FILTER
            + " ORDER BY r.created_at DESC, r.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AdminNewsRelationRow> pageRows(
            @Param("relationStatus") cn.zhishi.stock.news.domain.NewsRelationStatus relationStatus,
            @Param("targetType") cn.zhishi.stock.news.domain.NewsTargetType targetType,
            @Param("newsId") Long newsId,
            @Param("minConfidence") BigDecimal minConfidence,
            @Param("createdStartAt") LocalDateTime createdStartAt,
            @Param("createdEndAt") LocalDateTime createdEndAt,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*)" + FROM + FILTER + "</script>")
    long countRows(
            @Param("relationStatus") cn.zhishi.stock.news.domain.NewsRelationStatus relationStatus,
            @Param("targetType") cn.zhishi.stock.news.domain.NewsTargetType targetType,
            @Param("newsId") Long newsId,
            @Param("minConfidence") BigDecimal minConfidence,
            @Param("createdStartAt") LocalDateTime createdStartAt,
            @Param("createdEndAt") LocalDateTime createdEndAt);

    @Select("SELECT " + COLUMNS + FROM + " WHERE r.id = #{relationId}")
    AdminNewsRelationRow find(@Param("relationId") long relationId);

    @Select("SELECT " + COLUMNS + FROM
            + " WHERE r.news_id = #{newsId} AND r.target_type = #{targetType}"
            + " AND r.target_id = #{targetId}")
    AdminNewsRelationRow findByTarget(
            @Param("newsId") long newsId,
            @Param("targetType") cn.zhishi.stock.news.domain.NewsTargetType targetType,
            @Param("targetId") long targetId);

    @Select("SELECT COUNT(*) FROM stock_news WHERE id = #{newsId}")
    long countNews(@Param("newsId") long newsId);

    /** 手工关联：MANUAL + CONFIRMED、置信度 NULL（人的判断没有机器分数）。 */
    @Insert("""
            INSERT INTO stock_news_relation
              (id, news_id, target_type, target_id, relation_method,
               confidence_score, relation_status, reason_summary, reviewed_by, reviewed_at)
            VALUES
              (#{id}, #{newsId}, #{targetType}, #{targetId}, 'MANUAL',
               NULL, 'CONFIRMED', #{reasonSummary}, #{reviewedBy}, #{reviewedAt})
            """)
    int insertManual(
            @Param("id") long id,
            @Param("newsId") long newsId,
            @Param("targetType") cn.zhishi.stock.news.domain.NewsTargetType targetType,
            @Param("targetId") long targetId,
            @Param("reasonSummary") String reasonSummary,
            @Param("reviewedBy") long reviewedBy,
            @Param("reviewedAt") LocalDateTime reviewedAt);

    /**
     * 复核写路径（ADM-NEWS-06 的确认/拒绝与 ADM-NEWS-08 的删除共用）。
     *
     * <p>{@code reason_summary} 用 {@code COALESCE}：复核者没写理由时保留
     * 候选关系的原始依据——机器给的理由是"这条关联为什么存在"的事实，
     * 人工理由是"为什么这样处置"，前者不该被后者的缺席抹掉。
     */
    @Update("""
            UPDATE stock_news_relation SET
              relation_status = #{relationStatus},
              reason_summary  = COALESCE(#{reasonSummary}, reason_summary),
              reviewed_by     = #{reviewedBy},
              reviewed_at     = #{reviewedAt}
            WHERE id = #{relationId}
            """)
    int review(
            @Param("relationId") long relationId,
            @Param("relationStatus") cn.zhishi.stock.news.domain.NewsRelationStatus relationStatus,
            @Param("reasonSummary") String reasonSummary,
            @Param("reviewedBy") long reviewedBy,
            @Param("reviewedAt") LocalDateTime reviewedAt);
}
