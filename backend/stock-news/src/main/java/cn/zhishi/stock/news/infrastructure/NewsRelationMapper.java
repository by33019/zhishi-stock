package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code stock_news_relation} 的 SQL。
 *
 * <p>{@code findAll} 是**全量**取：资讯的读取路径要按目标类型/目标 id 过滤，
 * 而"哪些关联是 CONFIRMED"的判据必须与解析侧共用同一处（见 {@code NewsConfidence}）。
 * 把过滤下推到 SQL 会把这条判据抄成第二份。
 * 真实源接入后需要下推时，索引 {@code idx_news_relation_target_status} 已经就绪。
 */
@Mapper
public interface NewsRelationMapper {

    String COLUMNS = """
            id               AS relationId,
            news_id          AS newsId,
            target_type      AS targetType,
            target_id        AS targetId,
            relation_method  AS relationMethod,
            confidence_score AS confidenceScore,
            relation_status  AS relationStatus,
            reason_summary   AS reasonSummary
            """;

    @Select("SELECT " + COLUMNS + " FROM stock_news_relation")
    List<NewsRelationRow> findAll();

    @Select("SELECT " + COLUMNS + " FROM stock_news_relation WHERE news_id = #{newsId}")
    List<NewsRelationRow> findByNewsId(@Param("newsId") long newsId);

    @Insert("""
            INSERT INTO stock_news_relation
              (id, news_id, target_type, target_id, relation_method,
               confidence_score, relation_status, reason_summary)
            VALUES
              (#{relationId}, #{newsId}, #{targetType}, #{targetId}, #{relationMethod},
               #{confidenceScore}, #{relationStatus}, #{reasonSummary})
            """)
    void insert(
            @Param("relationId") long relationId,
            @Param("newsId") long newsId,
            @Param("targetType") NewsTargetType targetType,
            @Param("targetId") long targetId,
            @Param("relationMethod") NewsRelationMethod relationMethod,
            @Param("confidenceScore") BigDecimal confidenceScore,
            @Param("relationStatus") NewsRelationStatus relationStatus,
            @Param("reasonSummary") String reasonSummary);
}
