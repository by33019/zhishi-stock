package cn.zhishi.stock.news.infrastructure;

import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsRecord;
import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link NewsArticleStore} 的 MyBatis 实现：**哑存储**，不做任何业务判断。
 *
 * <p>唯一索引冲突原样抛 {@code DuplicateKeyException}，把"它意味着什么"留给用例层——
 * 在采集里它是"并发下另一个进程先写了这条来源 ID 对应的稿件"，不是错误。
 *
 * <h2>读取为什么在内存里做 JOIN</h2>
 * {@code findAll} 由三条简单查询组成（稿件、来源、关联），组合在内存里完成，而不是写一条
 * 28 列的 JOIN。三条查询各自都是"一次全取"，语义清楚；
 * 一条大 JOIN 会把三个表的列别名挤在一起，且 {@code stock_news_relation} 是一对多，
 * 要在 SQL 里聚合就得引入嵌套映射。
 *
 * <p><b>来源缺失的稿件被排除</b>（等价于 {@code INNER JOIN news_source}）：
 * 项目约束是 MySQL 无外键，来源与稿件的引用完整性由应用层保证；真出现孤儿行时，
 * 它既没有来源名也没有授权信息，**无法展示**，因此不进读取结果。
 * 这与"降级不失败"不冲突——降级指的是行情这类可缺数据，不是指失去授权依据的内容。
 */
public class MyBatisNewsArticleStore implements NewsArticleStore {

    private final NewsArticleMapper articles;
    private final NewsSourceStore sources;
    private final NewsRelationMapper relations;
    private final Clock clock;

    public MyBatisNewsArticleStore(
            NewsArticleMapper articles,
            NewsSourceStore sources,
            NewsRelationMapper relations,
            Clock clock) {
        this.articles = articles;
        this.sources = sources;
        this.relations = relations;
        this.clock = clock;
    }

    @Override
    public List<NewsRecord> findAll() {
        Map<Long, NewsSource> sourcesById = new LinkedHashMap<>();
        for (NewsSource source : sources.findAll()) {
            sourcesById.put(source.sourceId(), source);
        }
        Map<Long, List<NewsRelation>> relationsByNews = new LinkedHashMap<>();
        for (NewsRelationRow row : relations.findAll()) {
            relationsByNews
                    .computeIfAbsent(row.newsId(), key -> new ArrayList<>())
                    .add(toRelation(row));
        }
        List<NewsRecord> records = new ArrayList<>();
        for (NewsArticleRow row : articles.findAll()) {
            NewsSource source = sourcesById.get(row.sourceId());
            if (source == null) {
                continue;
            }
            records.add(new NewsRecord(
                    toArticle(row),
                    source,
                    relationsByNews.getOrDefault(row.newsId(), List.of())));
        }
        return List.copyOf(records);
    }

    @Override
    public Optional<NewsRecord> find(long newsId) {
        NewsArticleRow row = articles.find(newsId);
        if (row == null) {
            return Optional.empty();
        }
        return sources.findById(row.sourceId())
                .map(source -> new NewsRecord(toArticle(row), source, relationsOf(newsId)));
    }

    @Override
    public Optional<Long> findOriginalNewsIdByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(articles.findOriginalIdByFingerprint(fingerprint));
    }

    /**
     * {@code source_content_id} 为空时恒返回 {@code false}。
     *
     * <p>MySQL 的唯一索引把 {@code NULL} 视为互不相等，因此"没有来源侧稿件 id"的稿件
     * 无法靠来源 ID 幂等——它的幂等只能靠内容指纹。这里如实返回"不存在"，
     * 而不是假装查到了，让调用方去走指纹那条路径。
     */
    @Override
    public boolean existsBySourceContent(long sourceId, String sourceContentId) {
        if (sourceContentId == null || sourceContentId.isBlank()) {
            return false;
        }
        return articles.countBySourceContent(sourceId, sourceContentId) > 0;
    }

    @Override
    public void insert(NewsArticle article) {
        articles.insert(
                article.newsId(),
                article.sourceId(),
                article.sourceContentId(),
                article.newsType(),
                article.title(),
                article.summary(),
                article.authorName(),
                article.originalUrl(),
                article.languageCode(),
                toLocalDateTime(article.publishedAt()),
                toLocalDateTime(article.collectedAt()),
                article.contentFingerprint(),
                article.canonicalNewsId(),
                article.dedupStatus(),
                article.contentStatus(),
                article.originalAccessStatus(),
                toLocalDateTime(article.rightsExpireAt()));
    }

    private List<NewsRelation> relationsOf(long newsId) {
        return relations.findByNewsId(newsId).stream()
                .map(MyBatisNewsArticleStore::toRelation)
                .toList();
    }

    private NewsArticle toArticle(NewsArticleRow row) {
        return new NewsArticle(
                row.newsId(),
                row.sourceId(),
                row.sourceContentId(),
                row.newsType(),
                row.title(),
                row.summary(),
                row.authorName(),
                row.originalUrl(),
                row.languageCode(),
                toOffsetDateTime(row.publishedAt()),
                toOffsetDateTime(row.collectedAt()),
                row.contentFingerprint(),
                row.canonicalNewsId(),
                row.dedupStatus(),
                row.contentStatus(),
                row.originalAccessStatus(),
                toOffsetDateTime(row.rightsExpireAt()));
    }

    private static NewsRelation toRelation(NewsRelationRow row) {
        return new NewsRelation(
                row.relationId(),
                row.newsId(),
                row.targetType(),
                row.targetId(),
                row.relationMethod(),
                row.confidenceScore(),
                row.relationStatus(),
                row.reasonSummary());
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
