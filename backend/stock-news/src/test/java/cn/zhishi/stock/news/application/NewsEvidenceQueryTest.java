package cn.zhishi.stock.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsEvidence;
import cn.zhishi.stock.news.domain.NewsFixtures;
import cn.zhishi.stock.news.domain.NewsOriginalAccessStatus;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code NewsQueryService} 的 AI 证据取数（{@code NewsEvidenceProvider}）测试。
 *
 * <p>这一层是 {@code news_source.allow_ai_analysis} 的**消费侧**（M3-04 只落库了这个标记）。
 * 它守着一个不会报错的缺陷形态：一条资讯在资讯列表里看得见，却因为来源不允许
 * 而**不能**成为 AI 证据——两者口径若分叉，"AI 说没有依据"会与"列表里有 8 条"同时成立。
 */
class NewsEvidenceQueryTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final OffsetDateTime PUBLISHED = NewsFixtures.PUBLISHED;
    private static final String SECURITY_ID = "sim-600519";
    private static final String SECTOR_ID = "sim-bk0011";
    private static final String MARKET_ID = "CN";

    private static final SecurityIdentity SECURITY_600519 =
            NewsFixtures.security("600519", "模拟证券600519");

    private InMemoryNewsStores stores;
    private NewsQueryService service;

    @BeforeEach
    void setUp() {
        stores = new InMemoryNewsStores();
        stores.sourceRows.add(NewsFixtures.source(2, NewsFixtures.SOURCE_A));
        service = new NewsQueryService(
                stores.articles,
                stores.sources,
                new StubSecurityIdentityProvider(List.of(SECURITY_600519)),
                new StubSectorIdentityProvider(List.of(NewsFixtures.sector(11, "半导体"))),
                CLOCK);
    }

    private void addArticle(long newsId, long sourceId, String title) {
        stores.articleRows.add(NewsFixtures.article(newsId, sourceId, title, "摘要" + newsId));
    }

    private void addArticle(
            long newsId, long sourceId, String title, OffsetDateTime publishedAt,
            NewsDedupStatus dedup, NewsContentStatus status) {
        stores.articleRows.add(NewsFixtures.article(
                newsId, sourceId, title, "摘要" + newsId, dedup, null, status, publishedAt, null));
    }

    private void addRelation(long newsId, NewsTargetType type, long targetId, NewsRelationStatus status) {
        stores.relationRows.add(NewsFixtures.relation(newsId, type, targetId, status));
    }

    private List<NewsEvidence> evidenceForSecurity() {
        return service.evidenceFor(NewsTargetType.SECURITY, SECURITY_ID, null, null, 20);
    }

    // ---------- 关联判据 ----------

    @Test
    @DisplayName("只返回与目标确认关联的资讯")
    void onlyReturnsConfirmedRelations() {
        addArticle(1001, 2, "与 600519 确认关联");
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        addArticle(1002, 2, "与 600519 只是候选关联");
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CANDIDATE);
        addArticle(1003, 2, "与另一只证券确认关联");
        addRelation(1003, NewsTargetType.SECURITY, 999999L, NewsRelationStatus.CONFIRMED);
        addArticle(1004, 2, "没有任何关联");

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1001L);
    }

    @Test
    @DisplayName("已拒绝的关联同样不进 AI 证据")
    void rejectedRelationsAreExcluded() {
        addArticle(1001, 2, "被拒绝的关联");
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.REJECTED);
        // 对照组：同一目标下有一条确认关联，证明"空"不是取数整体失效导致的
        addArticle(1002, 2, "确认关联");
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1002L);
    }

    // ---------- allow_ai_analysis 消费侧 ----------

    @Test
    @DisplayName("来源不允许进入 AI 时，稿件在列表里可见但不能成为 AI 证据")
    void sourceWithoutAiIsVisibleInListButNotInEvidence() {
        stores.sourceRows.add(NewsFixtures.sourceWithoutAi(3, "SIM_MEDIA_NO_AI"));
        addArticle(1001, 3, "来自不允许进入 AI 的来源");
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        // 列表看得到——可见性判据与 AI 准入是两件事
        assertThat(service.list(NewsQuery.of(
                        java.util.Set.of(), null, null, null, null, null, null, null, null))
                .items())
                .extracting(cn.zhishi.stock.news.domain.NewsSummary::newsId)
                .containsExactly("1001");

        // AI 证据看不到（加一条允许进入 AI 的稿件作对照，证明取数本身是通的）
        addArticle(1002, 2, "来自允许进入 AI 的来源");
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1002L);
    }

    @Test
    @DisplayName("同一目标下，允许与不允许进入 AI 的来源混合时只返回允许的那些")
    void mixedSourcesReturnOnlyAiAllowedOnes() {
        stores.sourceRows.add(NewsFixtures.sourceWithoutAi(3, "SIM_MEDIA_NO_AI"));
        addArticle(1001, 2, "允许进入 AI");
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        addArticle(1002, 3, "不允许进入 AI");
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1001L);
    }

    // ---------- 可见性判据复用 ----------

    @Test
    @DisplayName("撤稿稿件不进 AI 证据——复用可见性过滤链，而不是另写一套")
    void withdrawnArticlesAreExcluded() {
        addArticle(1001, 2, "已撤稿", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.WITHDRAWN);
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        addArticle(1002, 2, "正常稿件", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1002L);
    }

    @Test
    @DisplayName("跨来源重复稿不进 AI 证据，只认主记录")
    void duplicatesAreExcluded() {
        addArticle(1001, 2, "主记录", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        stores.articleRows.add(NewsFixtures.article(
                1002, 2, "重复稿", "摘要", NewsDedupStatus.DUPLICATE, 1001L,
                NewsContentStatus.PUBLISHED, PUBLISHED, null));
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1001L);
    }

    @Test
    @DisplayName("授权已过期的来源其稿件不进 AI 证据")
    void expiredRightsSourceIsExcluded() {
        stores.sourceRows.add(NewsFixtures.expiredRights(
                4, "SIM_MEDIA_EXPIRED", java.time.LocalDate.of(2026, 9, 1)));
        addArticle(1001, 4, "授权过期的来源");
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        addArticle(1002, 2, "授权有效的来源");
        addRelation(1002, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1002L);
    }

    // ---------- 区间与排序 ----------

    @Test
    @DisplayName("区间过滤按发布时间含边界生效")
    void rangeFilterIsInclusive() {
        addArticle(1001, 2, "区间之前", PUBLISHED.minusDays(3), NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(1002, 2, "区间起点", PUBLISHED.minusDays(1), NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(1003, 2, "区间终点", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(1004, 2, "区间之后", PUBLISHED.plusDays(3), NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        for (long id = 1001; id <= 1004; id++) {
            addRelation(id, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        }

        assertThat(service.evidenceFor(
                        NewsTargetType.SECURITY,
                        SECURITY_ID,
                        PUBLISHED.minusDays(1),
                        PUBLISHED,
                        20))
                .extracting(NewsEvidence::newsId)
                .containsExactly(1003L, 1002L);
    }

    @Test
    @DisplayName("按发布时间倒序，同秒时按 ID 倒序——与资讯列表同口径")
    void sortedByPublishedDescThenIdDesc() {
        addArticle(1001, 2, "更早", PUBLISHED.minusHours(2), NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(1002, 2, "同秒 A", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(1003, 2, "同秒 B", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        for (long id = 1001; id <= 1003; id++) {
            addRelation(id, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        }

        assertThat(evidenceForSecurity())
                .extracting(NewsEvidence::newsId)
                .containsExactly(1003L, 1002L, 1001L);
    }

    // ---------- limit ----------

    @Test
    @DisplayName("limit 在过滤之后截断：不允许进入 AI 的稿件不占用配额")
    void limitAppliesAfterFiltering() {
        stores.sourceRows.add(NewsFixtures.sourceWithoutAi(3, "SIM_MEDIA_NO_AI"));
        // 不允许进入 AI 的稿件发布时间更晚，若先截断再过滤就会把它们占满 2 个名额
        addArticle(2001, 3, "不允许 A", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(2002, 3, "不允许 B", PUBLISHED, NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(2003, 2, "允许 A", PUBLISHED.minusHours(1), NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        addArticle(2004, 2, "允许 B", PUBLISHED.minusHours(2), NewsDedupStatus.ORIGINAL, NewsContentStatus.PUBLISHED);
        for (long id = 2001; id <= 2004; id++) {
            addRelation(id, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        }

        assertThat(service.evidenceFor(NewsTargetType.SECURITY, SECURITY_ID, null, null, 2))
                .extracting(NewsEvidence::newsId)
                .containsExactly(2003L, 2004L);
    }

    @Test
    @DisplayName("limit 小于 1 是调用方的编程错误，显式报错而不是静默返回空")
    void limitMustBePositive() {
        assertThatThrownBy(() -> service.evidenceFor(
                        NewsTargetType.SECURITY, SECURITY_ID, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    // ---------- 目标解析 ----------

    @Test
    @DisplayName("板块与市场目标同样可取证据")
    void supportsSectorAndMarketTargets() {
        addArticle(1001, 2, "板块资讯");
        addRelation(1001, NewsTargetType.SECTOR, 11L, NewsRelationStatus.CONFIRMED);
        addArticle(1002, 2, "市场资讯");
        addRelation(1002, NewsTargetType.MARKET, 1L, NewsRelationStatus.CONFIRMED);

        assertThat(service.evidenceFor(NewsTargetType.SECTOR, SECTOR_ID, null, null, 20))
                .extracting(NewsEvidence::newsId)
                .containsExactly(1001L);
        assertThat(service.evidenceFor(NewsTargetType.MARKET, MARKET_ID, null, null, 20))
                .extracting(NewsEvidence::newsId)
                .containsExactly(1002L);
    }

    @Test
    @DisplayName("目标标识无法解析时报错而不是返回空——空页会让打错的代码看起来像「这只票很安静」")
    void unknownTargetThrows() {
        assertThatThrownBy(() -> service.evidenceFor(
                        NewsTargetType.SECURITY, "sim-999999", null, null, 20))
                .isInstanceOf(NewsNotFoundException.class);
    }

    // ---------- 字段映射 ----------

    @Test
    @DisplayName("证据字段来自稿件与来源，原文地址与访问状态如实传递")
    void mapsFieldsFromArticleAndSource() {
        addArticle(1001, 2, "映射检查");
        addRelation(1001, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        NewsEvidence evidence = evidenceForSecurity().get(0);

        assertThat(evidence.newsId()).isEqualTo(1001L);
        assertThat(evidence.newsType()).isEqualTo(NewsType.NEWS);
        assertThat(evidence.title()).isEqualTo("映射检查");
        assertThat(evidence.summary()).isEqualTo("摘要1001");
        assertThat(evidence.sourceName()).isEqualTo("来源" + NewsFixtures.SOURCE_A);
        assertThat(evidence.publishedAt()).isEqualTo(PUBLISHED);
        // 夹具的稿件原文状态是 UNKNOWN（模拟源里 original_access_status 就是 UNKNOWN），
        // 这里断言"如实传递"，而不是断言某个特定取值
        assertThat(evidence.originalAccessStatus()).isEqualTo(NewsOriginalAccessStatus.UNKNOWN);
        assertThat(evidence.originalUrl()).isNotBlank();
    }
}
