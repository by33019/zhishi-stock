package cn.zhishi.stock.news.application;

import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsRecord;
import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.RelationCatalog;
import cn.zhishi.stock.news.domain.RelationCatalogProvider;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;

/**
 * 资讯域三个仓储端口的内存实现，供用例层单测使用。
 *
 * <p>刻意**模拟**两条真实存在的数据库行为，否则用例层的分支测不到：
 *
 * <ul>
 *   <li>{@code uk_stock_news_source_content}：插入同 {@code (来源, 来源侧稿件 id)} 时抛
 *       {@link DuplicateKeyException}（并发路径）；
 *   <li>来源缺失的稿件不出现在 {@code findAll()} 里（等价于 {@code INNER JOIN news_source}）。
 * </ul>
 *
 * <p>三个端口各自做成内部类，而不是让本类同时实现三者：{@link NewsSourceStore#findAll()} 与
 * {@link NewsArticleStore#findAll()} 同名但返回类型不同，一个 Java 类不可能同时实现二者。
 * 这是"两个端口本就独立"的必然结果，不是端口设计的缺陷。共享状态仍留在本类上，
 * 便于测试直接摆数据；三个端口实例从 {@link #sources} / {@link #articles} / {@link #relations} 取。
 */
final class InMemoryNewsStores {

    final List<NewsSource> sourceRows = new ArrayList<>();
    final List<NewsArticle> articleRows = new ArrayList<>();
    final List<NewsRelation> relationRows = new ArrayList<>();
    final List<Long> successSourceIds = new ArrayList<>();
    final List<Long> failureSourceIds = new ArrayList<>();
    final AtomicLong idSequence = new AtomicLong(1);

    /** 置为真时，下一次 {@code insert} 抛 {@code DuplicateKeyException}（模拟并发抢先写入）。 */
    boolean failNextInsert;

    final NewsSourceStore sources = new Sources();
    final NewsArticleStore articles = new Articles();
    final NewsRelationStore relations = new Relations();

    private final class Sources implements NewsSourceStore {

        @Override
        public List<NewsSource> findAll() {
            return List.copyOf(sourceRows);
        }

        @Override
        public Optional<NewsSource> findByCode(String sourceCode) {
            return sourceRows.stream()
                    .filter(source -> source.sourceCode().equals(sourceCode))
                    .findFirst();
        }

        @Override
        public Optional<NewsSource> findById(long sourceId) {
            return sourceRows.stream().filter(source -> source.sourceId() == sourceId).findFirst();
        }

        @Override
        public void ensureAll(Collection<NewsSource> declared) {
            for (NewsSource source : declared) {
                if (findByCode(source.sourceCode()).isEmpty()) {
                    sourceRows.add(source);
                }
            }
        }

        @Override
        public void recordSyncSuccess(Collection<Long> sourceIds, OffsetDateTime at) {
            successSourceIds.addAll(sourceIds);
            for (int index = 0; index < sourceRows.size(); index++) {
                NewsSource source = sourceRows.get(index);
                if (sourceIds.contains(source.sourceId())) {
                    sourceRows.set(index, withSyncTimes(source, at, source.lastFailureAt()));
                }
            }
        }

        @Override
        public void recordSyncFailure(Collection<Long> sourceIds, OffsetDateTime at) {
            failureSourceIds.addAll(sourceIds);
            for (int index = 0; index < sourceRows.size(); index++) {
                NewsSource source = sourceRows.get(index);
                if (sourceIds.contains(source.sourceId())) {
                    sourceRows.set(index, withSyncTimes(source, source.lastSuccessAt(), at));
                }
            }
        }
    }

    private final class Articles implements NewsArticleStore {

        @Override
        public List<NewsRecord> findAll() {
            Map<Long, NewsSource> byId = new LinkedHashMap<>();
            for (NewsSource source : sourceRows) {
                byId.put(source.sourceId(), source);
            }
            List<NewsRecord> records = new ArrayList<>();
            for (NewsArticle article : articleRows) {
                NewsSource source = byId.get(article.sourceId());
                if (source == null) {
                    continue;
                }
                List<NewsRelation> attached = relationRows.stream()
                        .filter(relation -> relation.newsId() == article.newsId())
                        .toList();
                records.add(new NewsRecord(article, source, attached));
            }
            return List.copyOf(records);
        }

        @Override
        public Optional<NewsRecord> find(long newsId) {
            return findAll().stream()
                    .filter(record -> record.article().newsId() == newsId)
                    .findFirst();
        }

        @Override
        public Optional<Long> findOriginalNewsIdByFingerprint(String fingerprint) {
            return articleRows.stream()
                    .filter(article -> article.dedupStatus() == NewsDedupStatus.ORIGINAL)
                    .filter(article -> article.contentFingerprint().equals(fingerprint))
                    .map(NewsArticle::newsId)
                    .findFirst();
        }

        @Override
        public boolean existsBySourceContent(long sourceId, String sourceContentId) {
            if (sourceContentId == null || sourceContentId.isBlank()) {
                return false;
            }
            return articleRows.stream().anyMatch(article ->
                    article.sourceId() == sourceId
                            && sourceContentId.equals(article.sourceContentId()));
        }

        @Override
        public void insert(NewsArticle article) {
            if (failNextInsert) {
                failNextInsert = false;
                throw new DuplicateKeyException("模拟唯一索引冲突");
            }
            if (existsBySourceContent(article.sourceId(), article.sourceContentId())) {
                throw new DuplicateKeyException("模拟唯一索引冲突");
            }
            articleRows.add(article);
        }
    }

    private final class Relations implements NewsRelationStore {

        @Override
        public void insertAll(List<NewsRelation> batch) {
            relationRows.addAll(batch);
        }
    }

    private static NewsSource withSyncTimes(
            NewsSource source, OffsetDateTime lastSuccessAt, OffsetDateTime lastFailureAt) {
        return new NewsSource(
                source.sourceId(),
                source.sourceCode(),
                source.sourceName(),
                source.sourceType(),
                source.homepageUrl(),
                source.authorizationStatus(),
                source.rightsValidFrom(),
                source.rightsValidTo(),
                source.allowAiAnalysis(),
                source.status(),
                lastSuccessAt,
                lastFailureAt,
                source.version());
    }
}

/** 由固定列表构造的证券身份桩：只认夹具里给出的那几只。 */
final class StubSecurityIdentityProvider implements SecurityIdentityProvider {

    private final Map<String, SecurityIdentity> byId = new LinkedHashMap<>();
    private final Map<Long, SecurityIdentity> byStorageId = new LinkedHashMap<>();

    StubSecurityIdentityProvider(Collection<SecurityIdentity> securities) {
        for (SecurityIdentity identity : securities) {
            byId.put(identity.securityId(), identity);
            byStorageId.put(identity.storageId(), identity);
        }
    }

    @Override
    public Optional<SecurityIdentity> resolve(String securityId) {
        return Optional.ofNullable(byId.get(securityId));
    }

    @Override
    public Map<String, SecurityIdentity> resolveAll(Collection<String> securityIds) {
        Map<String, SecurityIdentity> resolved = new LinkedHashMap<>();
        for (String securityId : securityIds) {
            SecurityIdentity identity = byId.get(securityId);
            if (identity != null) {
                resolved.put(securityId, identity);
            }
        }
        return Map.copyOf(resolved);
    }

    @Override
    public Map<Long, SecurityIdentity> findByStorageIds(Collection<Long> storageIds) {
        Map<Long, SecurityIdentity> resolved = new LinkedHashMap<>();
        for (Long storageId : storageIds) {
            SecurityIdentity identity = byStorageId.get(storageId);
            if (identity != null) {
                resolved.put(storageId, identity);
            }
        }
        return Map.copyOf(resolved);
    }
}

/** 由固定列表构造的板块身份桩。 */
final class StubSectorIdentityProvider implements SectorIdentityProvider {

    private final Map<String, SectorIdentity> byId = new LinkedHashMap<>();
    private final Map<Long, SectorIdentity> byStorageId = new LinkedHashMap<>();

    StubSectorIdentityProvider(Collection<SectorIdentity> sectors) {
        for (SectorIdentity identity : sectors) {
            byId.put(identity.sectorId(), identity);
            byStorageId.put(identity.storageId(), identity);
        }
    }

    @Override
    public Optional<SectorIdentity> resolve(String sectorId) {
        return Optional.ofNullable(byId.get(sectorId));
    }

    @Override
    public Map<String, SectorIdentity> resolveAll(Collection<String> sectorIds) {
        Map<String, SectorIdentity> resolved = new LinkedHashMap<>();
        for (String sectorId : sectorIds) {
            SectorIdentity identity = byId.get(sectorId);
            if (identity != null) {
                resolved.put(sectorId, identity);
            }
        }
        return Map.copyOf(resolved);
    }

    @Override
    public Map<Long, SectorIdentity> findByStorageIds(Collection<Long> storageIds) {
        Map<Long, SectorIdentity> resolved = new LinkedHashMap<>();
        for (Long storageId : storageIds) {
            SectorIdentity identity = byStorageId.get(storageId);
            if (identity != null) {
                resolved.put(storageId, identity);
            }
        }
        return Map.copyOf(resolved);
    }
}

/** 返回固定目录的桩，避免采集测试依赖行情域的四个真实端口。 */
final class StubRelationCatalogProvider implements RelationCatalogProvider {

    private final RelationCatalog catalog;

    StubRelationCatalogProvider(RelationCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public RelationCatalog load() {
        return catalog;
    }
}
