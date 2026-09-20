package cn.zhishi.stock.news.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsFeed;
import cn.zhishi.stock.news.domain.NewsFeedItem;
import cn.zhishi.stock.news.domain.NewsIngestionResult;
import cn.zhishi.stock.news.domain.NewsProvider;
import cn.zhishi.stock.news.domain.NewsRelation;
import cn.zhishi.stock.news.domain.NewsRelationMethod;
import cn.zhishi.stock.news.domain.NewsRelationResolver;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSourceType;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.RelationCatalog;
import cn.zhishi.stock.news.domain.NewsFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NewsIngestionService} 的行为测试。
 *
 * <p>这里钉的是**两条幂等路径**（来源 ID / 内容指纹）与**授权闸门**——
 * 三者失效时都不会报错，只会让库里多出重复内容或不该进来的内容。
 */
class NewsIngestionServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final RelationCatalog CATALOG = new RelationCatalog(
            List.of(NewsFixtures.security("600519", "模拟证券600519")),
            List.of(NewsFixtures.sector(11, "半导体")));

    private InMemoryNewsStores stores;

    @BeforeEach
    void setUp() {
        stores = new InMemoryNewsStores();
        stores.sourceRows.add(NewsFixtures.source(2, NewsFixtures.SOURCE_A));
        stores.sourceRows.add(NewsFixtures.source(3, NewsFixtures.SOURCE_B));
    }

    private NewsIngestionService service(NewsFeed feed) {
        return service(feed, List.of(NewsFixtures.source(2, NewsFixtures.SOURCE_A)));
    }

    private NewsIngestionService service(NewsFeed feed, List<NewsSource> declared) {
        NewsProvider provider = new NewsProvider() {
            @Override
            public NewsFeed fetch(OffsetDateTime since) {
                return feed;
            }

            @Override
            public List<NewsSource> sources() {
                return declared;
            }
        };
        return new NewsIngestionService(
                provider,
                stores.sources,
                stores.articles,
                stores.relations,
                new StubRelationCatalogProvider(CATALOG),
                new AtomicLong(1_000)::incrementAndGet,
                CLOCK);
    }

    private static NewsFeed feed(NewsFeedItem... items) {
        return new NewsFeed(List.of(items), OffsetDateTime.now(CLOCK));
    }

    @Test
    @DisplayName("正常采集：稿件入库为 ORIGINAL，并落库解析出的关联")
    void ingestsOriginalArticleWithRelations() {
        NewsIngestionResult result = service(feed(NewsFixtures.item(
                        "模拟证券600519 发布年度业绩预告", null)))
                .ingest(null);

        assertThat(result.fetchedCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.deduplicatedCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.relationCount()).isEqualTo(1);

        NewsArticle stored = stores.articleRows.get(0);
        assertThat(stored.dedupStatus()).isEqualTo(NewsDedupStatus.ORIGINAL);
        assertThat(stored.canonicalNewsId()).isNull();

        NewsRelation relation = stores.relationRows.get(0);
        assertThat(relation.targetType()).isEqualTo(NewsTargetType.SECURITY);
        assertThat(relation.targetId()).isEqualTo(600519L);
        assertThat(relation.relationStatus()).isEqualTo(NewsRelationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("C1 来源 ID 幂等：同一 (来源, 来源侧稿件 id) 再采一次整条跳过，库里仍只有一行")
    void skipsRedeliveredSourceContent() {
        NewsIngestionService service = service(feed(
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-1", "标题", null, List.of(), List.of(), null)));

        service.ingest(null);
        NewsIngestionResult second = service.ingest(null);

        assertThat(stores.articleRows).hasSize(1);
        assertThat(second.insertedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(1);
        assertThat(second.limitations()).anyMatch(text -> text.contains("来源 ID 幂等"));
    }

    @Test
    @DisplayName("C1 来源 ID 幂等**先于**指纹去重：内容被来源更新过也能拦住")
    void sourceIdIdempotencyWinsOverFingerprint() {
        NewsIngestionService service = service(feed(
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-1", "标题", "第一版", List.of(), List.of(), null)));

        service.ingest(null);
        // 同一来源侧稿件 ID，但内容变了——指纹拦不住，只有来源 ID 幂等能拦
        NewsIngestionResult second = service(feed(
                        NewsFixtures.item(NewsFixtures.SOURCE_A, "content-1", "标题", "第二版", List.of(), List.of(), null)))
                .ingest(null);

        assertThat(stores.articleRows).hasSize(1);
        assertThat(stores.articleRows.get(0).summary()).isEqualTo("第一版");
        assertThat(second.skippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("C2 内容指纹幂等：另一来源发同一内容 → DUPLICATE 且 canonicalNewsId 指向主记录")
    void marksCrossSourceDuplicate() {
        NewsIngestionService service = service(feed(
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-a", "同一件事", "同一段摘要", List.of(), List.of(), null),
                NewsFixtures.item(NewsFixtures.SOURCE_B, "content-b", "同一件事", "同一段摘要", List.of(), List.of(), null)));

        NewsIngestionResult result = service.ingest(null);

        assertThat(result.insertedCount()).isEqualTo(2);
        assertThat(result.deduplicatedCount()).isEqualTo(1);
        NewsArticle duplicate = stores.articleRows.get(1);
        assertThat(duplicate.dedupStatus()).isEqualTo(NewsDedupStatus.DUPLICATE);
        assertThat(duplicate.canonicalNewsId()).isEqualTo(stores.articleRows.get(0).newsId());
    }

    @Test
    @DisplayName("C4 重复稿不解析关联：关联只挂在主记录上")
    void duplicateGetsNoRelations() {
        NewsIngestionService service = service(feed(
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-a", "模拟证券600519 公告", null, List.of(), List.of(), null),
                NewsFixtures.item(NewsFixtures.SOURCE_B, "content-b", "模拟证券600519 公告", null, List.of(), List.of(), null)));

        NewsIngestionResult result = service.ingest(null);

        assertThat(result.deduplicatedCount()).isEqualTo(1);
        assertThat(result.relationCount()).isEqualTo(1);
        assertThat(stores.relationRows)
                .allSatisfy(relation -> assertThat(relation.newsId())
                        .isEqualTo(stores.articleRows.get(0).newsId()));
    }

    @Test
    @DisplayName("C7 授权闸门：未授权来源的稿件不落库，也不产出关联")
    void rejectsUnauthorizedSource() {
        stores.sourceRows.add(NewsFixtures.source(
                4,
                NewsFixtures.SOURCE_SUSPENDED,
                NewsSource.AuthorizationStatus.SUSPENDED,
                NewsSource.SourceStatus.ACTIVE));

        NewsIngestionResult result = service(feed(NewsFixtures.item(
                        NewsFixtures.SOURCE_SUSPENDED, "content-x", "某公司获大额订单", null, List.of(), List.of(), null)))
                .ingest(null);

        assertThat(stores.articleRows).isEmpty();
        assertThat(result.insertedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.limitations()).anyMatch(text -> text.contains("来源不可用"));
    }

    @Test
    @DisplayName("授权期已过的来源同样被挡在库外")
    void rejectsSourceWithExpiredRights() {
        stores.sourceRows.add(NewsFixtures.expiredRights(
                5, "SIM_OLD", LocalDate.of(2020, 1, 1)));

        NewsIngestionResult result = service(feed(NewsFixtures.item(
                        "SIM_OLD", "content-old", "旧来源稿件", null, List.of(), List.of(), null)))
                .ingest(null);

        assertThat(stores.articleRows).isEmpty();
        assertThat(result.skippedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("原文地址不在协议白名单内 → 不落库（契约 §11.2）")
    void rejectsNonHttpsOriginalUrl() {
        NewsFeedItem item = new NewsFeedItem(
                NewsFixtures.SOURCE_A, "content-http", cn.zhishi.stock.news.domain.NewsType.NEWS,
                "标题", null, "记者", "http://example.com/insecure", "zh-CN",
                NewsFixtures.PUBLISHED, List.of(), List.of(), null);

        NewsIngestionResult result = service(feed(item)).ingest(null);

        assertThat(stores.articleRows).isEmpty();
        assertThat(result.limitations()).anyMatch(text -> text.contains("协议白名单"));
    }

    @Test
    @DisplayName("来源未登记 → 跳过，且不因为它是新来源就自动放行")
    void rejectsUnregisteredSource() {
        NewsIngestionResult result = service(feed(NewsFixtures.item(
                        "SIM_UNKNOWN", "content-u", "标题", null, List.of(), List.of(), null)))
                .ingest(null);

        assertThat(stores.articleRows).isEmpty();
        assertThat(result.limitations()).anyMatch(text -> text.contains("来源未登记"));
    }

    @Test
    @DisplayName("Provider 声明的来源在采集前被登记（只插入缺失）")
    void registersDeclaredSources() {
        NewsSource declared = new NewsSource(
                9, "SIM_NEW", "新来源", NewsSourceType.EXCHANGE, null,
                NewsSource.AuthorizationStatus.AUTHORIZED, null, null, true,
                NewsSource.SourceStatus.ACTIVE, null, null, 0);

        service(feed(), List.of(declared)).ingest(null);

        assertThat(stores.sources.findByCode("SIM_NEW")).isPresent();
    }

    @Test
    @DisplayName("已登记来源不被 Provider 覆盖：授权状态是人工决定")
    void doesNotOverwriteExistingSource() {
        NewsSource declared = NewsFixtures.source(2, NewsFixtures.SOURCE_A);
        stores.sourceRows.set(0, NewsFixtures.source(
                2,
                NewsFixtures.SOURCE_A,
                NewsSource.AuthorizationStatus.SUSPENDED,
                NewsSource.SourceStatus.ACTIVE));

        service(feed(), List.of(declared)).ingest(null);

        assertThat(stores.sources.findByCode(NewsFixtures.SOURCE_A).orElseThrow().authorizationStatus())
                .isEqualTo(NewsSource.AuthorizationStatus.SUSPENDED);
    }

    @Test
    @DisplayName("并发下唯一索引冲突不中断整批：后续条目仍落库")
    void continuesAfterConcurrentDuplicateKey() {
        stores.failNextInsert = true;

        NewsIngestionResult result = service(feed(
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-1", "第一条", null, List.of(), List.of(), null),
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-2", "第二条", null, List.of(), List.of(), null)))
                .ingest(null);

        assertThat(stores.articleRows).hasSize(1);
        assertThat(stores.articleRows.get(0).sourceContentId()).isEqualTo("content-2");
        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.limitations()).anyMatch(text -> text.contains("并发"));
    }

    @Test
    @DisplayName("结果计数自洽：fetched = inserted + skipped")
    void countersAreConsistent() {
        NewsIngestionResult result = service(feed(
                NewsFixtures.item(NewsFixtures.SOURCE_A, "content-1", "第一条", null, List.of(), List.of(), null),
                NewsFixtures.item("SIM_UNKNOWN", "content-2", "第二条", null, List.of(), List.of(), null),
                NewsFixtures.item(NewsFixtures.SOURCE_B, "content-3", "第三条", null, List.of(), List.of(), null)))
                .ingest(null);

        assertThat(result.fetchedCount())
                .isEqualTo(result.insertedCount() + result.skippedCount());
    }

    @Test
    @DisplayName("采集成功后只把「覆盖到的可用来源」记为同步成功")
    void recordsSuccessOnlyForTouchedSources() {
        stores.sourceRows.add(NewsFixtures.source(7, "SIM_UNTOUCHED"));

        service(feed(NewsFixtures.item(
                        NewsFixtures.SOURCE_A, "content-1", "标题", null, List.of(), List.of(), null)))
                .ingest(null);

        assertThat(stores.successSourceIds).contains(2L).doesNotContain(7L);
    }

    @Test
    @DisplayName("空批次不抛异常：没有任何新内容不是错误")
    void toleratesEmptyFeed() {
        NewsIngestionResult result = service(feed()).ingest(null);

        assertThat(result.fetchedCount()).isZero();
        assertThat(result.insertedCount()).isZero();
        assertThat(result.nothingInserted()).isTrue();
    }

    @Test
    @DisplayName("recordSyncFailure 把全部可用来源标记为失败")
    void recordSyncFailureMarksUsableSources() {
        stores.sourceRows.add(NewsFixtures.source(
                4,
                NewsFixtures.SOURCE_SUSPENDED,
                NewsSource.AuthorizationStatus.SUSPENDED,
                NewsSource.SourceStatus.ACTIVE));

        service(feed()).recordSyncFailure(OffsetDateTime.now(CLOCK));

        assertThat(stores.failureSourceIds).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("结构化市场提示产出 MARKET 关联")
    void resolvesMarketRelation() {
        service(feed(NewsFixtures.item(
                        "大盘综述", null, List.of(), List.of(), "CN")))
                .ingest(null);

        assertThat(stores.relationRows)
                .anySatisfy(relation -> assertThat(relation.targetType())
                        .isEqualTo(NewsTargetType.MARKET));
    }

    @Test
    @DisplayName("解析器产出的每条关联都带依据摘要，落库时原样保留")
    void keepsRelationReason() {
        service(feed(NewsFixtures.item("半导体行业景气度回升", null))).ingest(null);

        NewsRelation relation = stores.relationRows.get(0);
        assertThat(relation.reasonSummary()).contains("半导体");
        assertThat(relation.relationMethod()).isEqualTo(NewsRelationMethod.RULE);
    }

    @Test
    @DisplayName("解析器直接可用：采集服务不自己判关联规则")
    void resolverIsReused() {
        NewsRelationResolver resolver = new NewsRelationResolver(CATALOG);

        assertThat(resolver.resolve(NewsFixtures.item("模拟证券600519 发布公告", null)))
                .isNotEmpty();
    }
}
