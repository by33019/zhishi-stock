package cn.zhishi.stock.news.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code stock_news} 的 SQL。
 *
 * <p>两点刻意的写法：
 *
 * <ul>
 *   <li>{@code findOriginalIdByFingerprint} 只找 {@code dedup_status = 'ORIGINAL'} 的行。
 *       若允许指向另一条重复稿，{@code canonical_news_id} 会连成链而不是星形，
 *       查询时"折叠到主记录"就要递归——而递归在 SQL 里很难写对；
 *   <li>插入不吞唯一索引冲突：{@code uk_stock_news_source_content} 是"来源 ID 幂等"的
 *       唯一权威，撞索引意味着**并发下另一个采集进程先写了**，由用例层决定怎么记。
 *       在这里 {@code INSERT IGNORE} 会让"跳过"与"写入失败"变得不可区分。
 * </ul>
 */
@Mapper
public interface NewsArticleMapper {

    String COLUMNS = """
            id                     AS newsId,
            source_id              AS sourceId,
            source_content_id      AS sourceContentId,
            news_type              AS newsType,
            title                  AS title,
            authorized_summary     AS summary,
            author_name            AS authorName,
            original_url           AS originalUrl,
            language_code          AS languageCode,
            published_at           AS publishedAt,
            collected_at           AS collectedAt,
            content_fingerprint    AS contentFingerprint,
            canonical_news_id      AS canonicalNewsId,
            dedup_status           AS dedupStatus,
            content_status         AS contentStatus,
            original_access_status AS originalAccessStatus,
            rights_expire_at       AS rightsExpireAt
            """;

    @Select("SELECT " + COLUMNS + " FROM stock_news")
    List<NewsArticleRow> findAll();

    @Select("SELECT " + COLUMNS + " FROM stock_news WHERE id = #{newsId}")
    NewsArticleRow find(@Param("newsId") long newsId);

    @Select("""
            SELECT id FROM stock_news
             WHERE content_fingerprint = #{fingerprint} AND dedup_status = 'ORIGINAL'
             ORDER BY id
             LIMIT 1
            """)
    Long findOriginalIdByFingerprint(@Param("fingerprint") String fingerprint);

    @Select("""
            SELECT COUNT(*) FROM stock_news
             WHERE source_id = #{sourceId} AND source_content_id = #{sourceContentId}
            """)
    int countBySourceContent(
            @Param("sourceId") long sourceId,
            @Param("sourceContentId") String sourceContentId);

    @Insert("""
            INSERT INTO stock_news
              (id, source_id, source_content_id, news_type, title, authorized_summary,
               author_name, original_url, language_code, published_at, collected_at,
               content_fingerprint, canonical_news_id, dedup_status, content_status,
               original_access_status, rights_expire_at)
            VALUES
              (#{newsId}, #{sourceId}, #{sourceContentId}, #{newsType}, #{title}, #{summary},
               #{authorName}, #{originalUrl}, #{languageCode}, #{publishedAt}, #{collectedAt},
               #{contentFingerprint}, #{canonicalNewsId}, #{dedupStatus}, #{contentStatus},
               #{originalAccessStatus}, #{rightsExpireAt})
            """)
    void insert(
            @Param("newsId") long newsId,
            @Param("sourceId") long sourceId,
            @Param("sourceContentId") String sourceContentId,
            @Param("newsType") NewsType newsType,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("authorName") String authorName,
            @Param("originalUrl") String originalUrl,
            @Param("languageCode") String languageCode,
            @Param("publishedAt") LocalDateTime publishedAt,
            @Param("collectedAt") LocalDateTime collectedAt,
            @Param("contentFingerprint") String contentFingerprint,
            @Param("canonicalNewsId") Long canonicalNewsId,
            @Param("dedupStatus") NewsDedupStatus dedupStatus,
            @Param("contentStatus") NewsContentStatus contentStatus,
            @Param("originalAccessStatus") NewsOriginalAccessStatus originalAccessStatus,
            @Param("rightsExpireAt") LocalDateTime rightsExpireAt);
}
