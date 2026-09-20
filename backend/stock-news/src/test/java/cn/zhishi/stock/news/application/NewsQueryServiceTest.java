package cn.zhishi.stock.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.news.domain.NewsArticle;
import cn.zhishi.stock.news.domain.NewsContentStatus;
import cn.zhishi.stock.news.domain.NewsDetail;
import cn.zhishi.stock.news.domain.NewsDedupStatus;
import cn.zhishi.stock.news.domain.NewsFixtures;
import cn.zhishi.stock.news.domain.NewsOptions;
import cn.zhishi.stock.news.domain.NewsPage;
import cn.zhishi.stock.news.domain.NewsRelationStatus;
import cn.zhishi.stock.news.domain.NewsSource;
import cn.zhishi.stock.news.domain.NewsSummary;
import cn.zhishi.stock.news.domain.NewsSyncStatus;
import cn.zhishi.stock.news.domain.NewsTargetType;
import cn.zhishi.stock.news.domain.NewsType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link NewsQueryService} 的过滤链与映射测试。
 *
 * <p>可见性判据失效时**不会报错**：列表里会多出一条未授权的、已撤稿的或重复的资讯。
 * 因此每一条判据都在这里单独钉一遍。
 */
class NewsQueryServiceTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-18T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final SecurityIdentity SECURITY_600519 =
            NewsFixtures.security("600519", "模拟证券600519");
    private static final SecurityIdentity SECURITY_000001 =
            NewsFixtures.security("000001", "模拟证券000001");

    private InMemoryNewsStores stores;
    private NewsQueryService service;

    @BeforeEach
    void setUp() {
        stores = new InMemoryNewsStores();
        stores.sourceRows.add(NewsFixtures.source(2, NewsFixtures.SOURCE_A));
        stores.sourceRows.add(NewsFixtures.source(3, NewsFixtures.SOURCE_B));
        service = new NewsQueryService(
                stores.articles,
                stores.sources,
                new StubSecurityIdentityProvider(List.of(SECURITY_600519, SECURITY_000001)),
                new StubSectorIdentityProvider(List.of(NewsFixtures.sector(11, "半导体"))),
                CLOCK);
    }

    private NewsSummary addArticle(long newsId, long sourceId, String title) {
        stores.articleRows.add(NewsFixtures.article(newsId, sourceId, title, "摘要"));
        return null;
    }

    private void addRelation(long newsId, NewsTargetType type, long targetId, NewsRelationStatus status) {
        stores.relationRows.add(NewsFixtures.relation(newsId, type, targetId, status));
    }

    private PageData<NewsSummary> listAll() {
        return service.list(NewsQuery.of(Set.of(), null, null, null, null, null, null, null, null));
    }

    // ---------- 可见性过滤链 ----------

    @Test
    @DisplayName("C8 已撤稿的稿件不出现在列表里（但按 id 取详情时明确报 NEWS_WITHDRAWN）")
    void hidesWithdrawnFromList() {
        stores.articleRows.add(NewsFixtures.article(
                1L, 2L, "撤稿稿件", null, NewsDedupStatus.ORIGINAL, null,
                NewsContentStatus.WITHDRAWN, null));

        assertThat(listAll().items()).isEmpty();
        assertThatThrownBy(() -> service.detail("1"))
                .isInstanceOf(NewsNotFoundException.class)
                .hasMessageContaining("已撤稿");
    }

    @Test
    @DisplayName("C10 重复稿不出现在列表里（折叠到主记录）")
    void hidesDuplicatesFromList() {
        stores.articleRows.add(NewsFixtures.article(1L, 2L, "主记录", null));
        stores.articleRows.add(NewsFixtures.article(
                2L, 3L, "重复稿", null, NewsDedupStatus.DUPLICATE, 1L,
                NewsContentStatus.PUBLISHED, null));

        assertThat(listAll().items()).extracting(NewsSummary::newsId).containsExactly("1");
    }

    @Test
    @DisplayName("C7 未授权来源的稿件不出现在列表里")
    void hidesUnauthorizedSource() {
        stores.sourceRows.set(0, NewsFixtures.source(
                2,
                NewsFixtures.SOURCE_A,
                NewsSource.AuthorizationStatus.SUSPENDED,
                NewsSource.SourceStatus.ACTIVE));
        addArticle(1L, 2L, "来自停用来源");
        addArticle(2L, 3L, "来自正常来源");

        assertThat(listAll().items()).extracting(NewsSummary::newsId).containsExactly("2");
    }

    @Test
    @DisplayName("C9 授权已过期的单条内容不出现在列表里")
    void hidesExpiredContent() {
        stores.articleRows.add(NewsFixtures.article(
                1L, 2L, "过期内容", null, NewsDedupStatus.ORIGINAL, null,
                NewsContentStatus.PUBLISHED, OffsetDateTime.now(CLOCK).minusDays(1)));
        addArticle(2L, 2L, "有效内容");

        assertThat(listAll().items()).extracting(NewsSummary::newsId).containsExactly("2");
    }

    @Test
    @DisplayName("C6 低置信候选关联不进列表，也不出现在 relations 里")
    void hidesCandidateRelations() {
        addArticle(1L, 2L, "有候选关联的稿件");
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CANDIDATE);

        PageData<NewsSummary> page = listAll();

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).relations()).isEmpty();
        // 但按 id 取详情同样不返回候选关联——"不进 AI 证据"对所有前台接口一致
        assertThat(service.detail("1").relations()).isEmpty();
    }

    @Test
    @DisplayName("已确认关联出现在列表里，且 targetId 是对外标识而不是代理键")
    void exposesConfirmedRelationWithOutwardId() {
        addArticle(1L, 2L, "有确认关联的稿件");
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        NewsSummary summary = listAll().items().get(0);

        assertThat(summary.relations()).hasSize(1);
        assertThat(summary.relations().get(0).targetId()).isEqualTo("sim-600519");
        assertThat(summary.relations().get(0).targetCode()).isEqualTo("600519");
        assertThat(summary.relations().get(0).targetName()).isEqualTo("模拟证券600519");
    }

    @Test
    @DisplayName("C11 排序：published_at 降序，同刻按 newsId 降序")
    void sortsByPublishedAtThenNewsId() {
        addArticle(1L, 2L, "第一条");
        addArticle(3L, 2L, "第三条");

        assertThat(listAll().items())
                .extracting(NewsSummary::newsId)
                .containsExactly("3", "1");
    }

    // ---------- STK-10 / SEC-07 ----------

    @Test
    @DisplayName("C13 STK-10 只返回该证券的确认关联资讯，不因板块关系泛化")
    void filtersBySecurityOnly() {
        addArticle(1L, 2L, "关于 600519");
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        addArticle(2L, 2L, "关于某板块");
        addRelation(2L, NewsTargetType.SECTOR, 11L, NewsRelationStatus.CONFIRMED);

        NewsPage page = service.bySecurity("sim-600519", null, null, null, null, null);

        assertThat(page.items()).extracting(NewsSummary::newsId).containsExactly("1");
    }

    @Test
    @DisplayName("C12 STK-10 的 securityId 解析不到 → SECURITY_NOT_FOUND（不是空页）")
    void rejectsUnknownSecurityId() {
        assertThatThrownBy(() -> service.bySecurity("sim-999999", null, null, null, null, null))
                .isInstanceOf(NewsNotFoundException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("SEC-07 的 sectorId 解析不到 → SECTOR_NOT_FOUND")
    void rejectsUnknownSectorId() {
        assertThatThrownBy(() -> service.bySector("sim-bk9999", null, null, null, null, null))
                .isInstanceOf(NewsNotFoundException.class)
                .hasMessageContaining("板块");
    }

    @Test
    @DisplayName("SEC-07 返回该板块确认关联的资讯")
    void filtersBySector() {
        addArticle(1L, 2L, "板块资讯");
        addRelation(1L, NewsTargetType.SECTOR, 11L, NewsRelationStatus.CONFIRMED);

        assertThat(service.bySector("sim-bk0011", null, null, null, null, null).items())
                .extracting(NewsSummary::newsId)
                .containsExactly("1");
    }

    // ---------- NEWS-02 ----------

    @Test
    @DisplayName("NEWS-02：没有任何关联的稿件也能打开（列表里出现过就必须点得进去）")
    void detailWorksWithoutRelations() {
        addArticle(1L, 2L, "无关联稿件");

        NewsDetail detail = service.detail("1");

        assertThat(detail.newsId()).isEqualTo("1");
        assertThat(detail.relations()).isEmpty();
        assertThat(detail.copyrightNotice()).contains("来源");
    }

    @Test
    @DisplayName("NEWS-02：重复稿的 id 折叠到主记录（契约 §4.3）")
    void detailFoldsDuplicateToCanonical() {
        stores.articleRows.add(NewsFixtures.article(1L, 2L, "主记录", null));
        stores.articleRows.add(NewsFixtures.article(
                2L, 3L, "重复稿", null, NewsDedupStatus.DUPLICATE, 1L,
                NewsContentStatus.PUBLISHED, null));

        assertThat(service.detail("2").newsId()).isEqualTo("1");
    }

    @Test
    @DisplayName("NEWS-02：id 不存在 → NEWS_NOT_FOUND（不是 400）")
    void detailRejectsUnknownId() {
        assertThatThrownBy(() -> service.detail("999"))
                .isInstanceOf(NewsNotFoundException.class);
        assertThatThrownBy(() -> service.detail("not-a-number"))
                .isInstanceOf(NewsNotFoundException.class);
    }

    @Test
    @DisplayName("NEWS-02：来源授权已失效 → NEWS_RIGHTS_EXPIRED")
    void detailRejectsExpiredRights() {
        stores.articleRows.add(NewsFixtures.article(
                1L, 2L, "过期内容", null, NewsDedupStatus.ORIGINAL, null,
                NewsContentStatus.PUBLISHED, OffsetDateTime.now(CLOCK).minusMinutes(1)));

        assertThatThrownBy(() -> service.detail("1"))
                .isInstanceOf(NewsNotFoundException.class)
                .hasMessageContaining("授权已过期");
    }

    // ---------- NEWS-03 ----------

    @Test
    @DisplayName("NEWS-03：没有任何可用来源 → UNAVAILABLE，delaySeconds 为 null（不是 0）")
    void syncStatusUnavailableWithoutSources() {
        stores.sourceRows.clear();

        NewsSyncStatus status = service.syncStatus();

        assertThat(status.overallStatus()).isEqualTo(NewsSyncStatus.OverallStatus.UNAVAILABLE);
        assertThat(status.delaySeconds()).isNull();
        assertThat(status.lastSuccessfulSyncAt()).isNull();
        assertThat(status.availableSourceCount()).isZero();
    }

    @Test
    @DisplayName("NEWS-03：全部来源可用且最近同步成功 → OK，delaySeconds 由时钟算出")
    void syncStatusOk() {
        OffsetDateTime syncedAt = OffsetDateTime.now(CLOCK).minus(Duration.ofSeconds(120));
        stores.sourceRows.set(0, NewsFixtures.source(
                2, NewsFixtures.SOURCE_A, NewsSource.AuthorizationStatus.AUTHORIZED,
                NewsSource.SourceStatus.ACTIVE, syncedAt, null));
        stores.sourceRows.set(1, NewsFixtures.source(
                3, NewsFixtures.SOURCE_B, NewsSource.AuthorizationStatus.AUTHORIZED,
                NewsSource.SourceStatus.ACTIVE, syncedAt, null));

        NewsSyncStatus status = service.syncStatus();

        assertThat(status.overallStatus()).isEqualTo(NewsSyncStatus.OverallStatus.OK);
        assertThat(status.delaySeconds()).isEqualTo(120);
        assertThat(status.availableSourceCount()).isEqualTo(2);
        assertThat(status.failedSourceCount()).isZero();
    }

    @Test
    @DisplayName("NEWS-03：有来源失败 → DEGRADED")
    void syncStatusDegraded() {
        OffsetDateTime syncedAt = OffsetDateTime.now(CLOCK).minus(Duration.ofSeconds(60));
        stores.sourceRows.set(0, NewsFixtures.source(
                2, NewsFixtures.SOURCE_A, NewsSource.AuthorizationStatus.AUTHORIZED,
                NewsSource.SourceStatus.DEGRADED, syncedAt, OffsetDateTime.now(CLOCK)));
        stores.sourceRows.set(1, NewsFixtures.source(
                3, NewsFixtures.SOURCE_B, NewsSource.AuthorizationStatus.AUTHORIZED,
                NewsSource.SourceStatus.ACTIVE, syncedAt, null));

        NewsSyncStatus status = service.syncStatus();

        assertThat(status.overallStatus()).isEqualTo(NewsSyncStatus.OverallStatus.DEGRADED);
        assertThat(status.failedSourceCount()).isEqualTo(1);
    }

    // ---------- NEWS-04 ----------

    @Test
    @DisplayName("NEWS-04：筛选项取自枚举，时间范围取自当前可见资讯")
    void optionsReflectVisibleContent() {
        addArticle(1L, 2L, "唯一一条");

        NewsOptions options = service.options();

        assertThat(options.newsTypes()).containsExactlyElementsOf(NewsType.codes());
        assertThat(options.sourceTypes()).contains("MEDIA", "EXCHANGE", "REGULATOR", "COMPANY");
        assertThat(options.availableTimeRange().startAt()).isEqualTo(NewsFixtures.PUBLISHED);
        assertThat(options.filterRules()).contains("已确认关联");
    }

    @Test
    @DisplayName("NEWS-04：没有任何可见资讯时时间范围为空（不编造一个范围）")
    void optionsRangeIsEmptyWithoutContent() {
        NewsOptions options = service.options();

        assertThat(options.availableTimeRange().isEmpty()).isTrue();
    }

    // ---------- 附加条件 ----------

    @Test
    @DisplayName("newsTypes 过滤生效；非法取值 → INVALID_REQUEST 而不是静默忽略")
    void filtersByNewsType() {
        addArticle(1L, 2L, "普通资讯");

        assertThat(service.list(NewsQuery.of(
                        Set.of(NewsType.ANNOUNCEMENT), null, null, null, null, null, null, null, null))
                .items())
                .isEmpty();
        assertThat(service.list(NewsQuery.of(
                        Set.of(NewsType.NEWS), null, null, null, null, null, null, null, null))
                .items())
                .hasSize(1);
        assertThatThrownBy(() -> service.bySecurity(
                        "sim-600519", "NEWSX", null, null, null, null))
                .isInstanceOf(InvalidNewsQueryException.class)
                .hasMessageContaining("不支持的资讯类型");
    }

    @Test
    @DisplayName("keyword 匹配标题或摘要，大小写不敏感")
    void filtersByKeyword() {
        addArticle(1L, 2L, "关于人工智能的进展");

        assertThat(service.list(NewsQuery.of(
                        Set.of(), null, null, null, null, null, "人工智能", null, null))
                .items())
                .hasSize(1);
        assertThat(service.list(NewsQuery.of(
                        Set.of(), null, null, null, null, null, "不存在", null, null))
                .items())
                .isEmpty();
    }

    @Test
    @DisplayName("分页的 total 是过滤后的总数，不是全表条数")
    void paginatesFilteredTotal() {
        addArticle(1L, 2L, "第一条");
        addArticle(2L, 2L, "第二条");
        addArticle(3L, 2L, "第三条");

        PageData<NewsSummary> page = service.list(
                NewsQuery.of(Set.of(), null, null, null, null, null, null, 1, 2));

        assertThat(page.items()).hasSize(2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("page/size 越界 → INVALID_REQUEST")
    void rejectsInvalidPaging() {
        assertThatThrownBy(() -> service.list(
                        NewsQuery.of(Set.of(), null, null, null, null, null, null, 0, 10)))
                .isInstanceOf(InvalidNewsQueryException.class);
        assertThatThrownBy(() -> service.list(
                        NewsQuery.of(Set.of(), null, null, null, null, null, null, 1, 101)))
                .isInstanceOf(InvalidNewsQueryException.class);
    }

    @Test
    @DisplayName("时间区间端点用纯日期时，endAt 取当日终点（否则当天发布的资讯会被排除）")
    void dateOnlyEndAtCoversWholeDay() {
        addArticle(1L, 2L, "当天发布的资讯");

        assertThat(service.bySecurity("sim-600519", null, "2026-09-18", "2026-09-18", null, null)
                        .items())
                .hasSize(0);
        // 上面这条稿件没有关联，先补一条关联再断言区间
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(service.bySecurity("sim-600519", null, "2026-09-18", "2026-09-18", null, null)
                        .items())
                .hasSize(1);
        assertThat(service.bySecurity("sim-600519", null, "2026-09-17", "2026-09-17", null, null)
                        .items())
                .isEmpty();
    }

    // ---------- countSince ----------

    @Test
    @DisplayName("C14 countSince 只统计确认的证券关联；没有任何资讯的证券不出现在结果里")
    void countsConfirmedSecurityRelationsOnly() {
        addArticle(1L, 2L, "关于 600519");
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);
        addArticle(2L, 2L, "关于 000001 的候选");
        addRelation(2L, NewsTargetType.SECURITY, 1L, NewsRelationStatus.CANDIDATE);
        addArticle(3L, 2L, "关于某板块");
        addRelation(3L, NewsTargetType.SECTOR, 11L, NewsRelationStatus.CONFIRMED);

        Map<String, Integer> counts = service.countSince(
                List.of("sim-600519", "sim-000001"), null);

        assertThat(counts).containsExactly(Map.entry("sim-600519", 1));
    }

    @Test
    @DisplayName("C15 countSince 空集合直接返回空，不触发任何查询")
    void countsNothingForEmptyInput() {
        assertThat(service.countSince(List.of(), null)).isEmpty();
        assertThat(service.countSince(null, null)).isEmpty();
    }

    @Test
    @DisplayName("countSince 的 since 是开区间：早于或等于 since 的资讯不计入")
    void countSinceIsExclusive() {
        addArticle(1L, 2L, "关于 600519");
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(service.countSince(List.of("sim-600519"), NewsFixtures.PUBLISHED)).isEmpty();
        assertThat(service.countSince(List.of("sim-600519"), NewsFixtures.PUBLISHED.minusDays(1)))
                .containsEntry("sim-600519", 1);
    }

    @Test
    @DisplayName("countSince 尊重可见性：撤稿稿件的关联不计入")
    void countSinceRespectsVisibility() {
        stores.articleRows.add(NewsFixtures.article(
                1L, 2L, "撤稿稿件", null, NewsDedupStatus.ORIGINAL, null,
                NewsContentStatus.WITHDRAWN, null));
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(service.countSince(List.of("sim-600519"), null)).isEmpty();
    }

    @Test
    @DisplayName("countSince 用对外标识作键，不用代理键")
    void countSinceKeysAreOutwardIds() {
        addArticle(1L, 2L, "关于 600519");
        addRelation(1L, NewsTargetType.SECURITY, 600519L, NewsRelationStatus.CONFIRMED);

        assertThat(service.countSince(List.of("sim-600519"), null))
                .containsOnlyKeys("sim-600519");
    }

    @Test
    @DisplayName("LocalDate 口径与时钟一致：授权期按当天判定")
    void usesClockDateForRights() {
        stores.sourceRows.set(0, NewsFixtures.expiredRights(2, NewsFixtures.SOURCE_A, LocalDate.of(2026, 9, 17)));
        addArticle(1L, 2L, "来自授权已过期的来源");

        assertThat(listAll().items()).isEmpty();
    }

    // ---------- NEWS-01 响应封套 ----------

    @Test
    @DisplayName("browse 与 list 返回同一批数据：封套只是加了两项同步元信息")
    void browseMatchesListItems() {
        addArticle(1L, 2L, "第一条");
        addArticle(2L, 3L, "第二条");

        NewsPage page = service.browse(null, null, null, null, null, null, null, null, null);

        assertThat(page.items())
                .extracting(NewsSummary::newsId)
                .containsExactlyElementsOf(listAll().items().stream()
                        .map(NewsSummary::newsId)
                        .toList());
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("封套里的同步元信息与 NEWS-03 同源：不是各算一遍")
    void browseCarriesSyncMetadataFromSyncStatus() {
        OffsetDateTime syncedAt = OffsetDateTime.now(CLOCK).minusSeconds(30);
        stores.sources.recordSyncSuccess(List.of(2L, 3L), syncedAt);

        NewsPage page = service.browse(null, null, null, null, null, null, null, null, null);
        NewsSyncStatus status = service.syncStatus();

        assertThat(page.lastSuccessfulSyncAt()).isEqualTo(status.lastSuccessfulSyncAt());
        assertThat(page.dataStatus()).isEqualTo(status.dataStatus());
    }

    @Test
    @DisplayName("从未成功同步过 → dataStatus=UNAVAILABLE，且 lastSuccessfulSyncAt 为 null 而不是某个时刻")
    void dataStatusIsUnavailableBeforeFirstSync() {
        NewsPage page = service.browse(null, null, null, null, null, null, null, null, null);

        assertThat(page.dataStatus()).isEqualTo(MarketOverview.DataStatus.UNAVAILABLE);
        assertThat(page.lastSuccessfulSyncAt()).isNull();
    }

    @Test
    @DisplayName("browse 解析 newsTypes 逗号分隔多值；未知取值报错而不是静默忽略")
    void browseParsesNewsTypes() {
        addArticle(1L, 2L, "普通资讯");

        assertThat(service.browse("NEWS", null, null, null, null, null, null, null, null).items())
                .hasSize(1);
        assertThat(service.browse("ANNOUNCEMENT", null, null, null, null, null, null, null, null)
                        .items())
                .isEmpty();
        assertThatThrownBy(() -> service.browse(
                        "NEWSX", null, null, null, null, null, null, null, null))
                .isInstanceOf(InvalidNewsQueryException.class)
                .hasMessageContaining("不支持的资讯类型");
    }

    @Test
    @DisplayName("browse 把 marketCode 交给同一套市场映射：不支持的市场报错")
    void browseRejectsUnknownMarket() {
        assertThatThrownBy(() -> service.browse(
                        null, null, null, "US", null, null, null, null, null))
                .isInstanceOf(InvalidNewsQueryException.class);
    }

    // ---------- dataStatus 映射 ----------

    @Test
    @DisplayName("dataStatus 是 syncStatus 的投影，三态一一对应")
    void mapsDataStatusFromOverallStatus() {
        assertThat(new NewsSyncStatus(
                        NewsSyncStatus.OverallStatus.OK, OffsetDateTime.now(CLOCK), 0L, 3, 0)
                        .dataStatus())
                .isEqualTo(MarketOverview.DataStatus.REALTIME);
        assertThat(new NewsSyncStatus(
                        NewsSyncStatus.OverallStatus.DEGRADED, OffsetDateTime.now(CLOCK), 5L, 3, 1)
                        .dataStatus())
                .isEqualTo(MarketOverview.DataStatus.DELAYED);
        assertThat(new NewsSyncStatus(
                        NewsSyncStatus.OverallStatus.UNAVAILABLE, null, null, 0, 0)
                        .dataStatus())
                .isEqualTo(MarketOverview.DataStatus.UNAVAILABLE);
    }

    // ---------- keyword 长度上限 ----------

    @Test
    @DisplayName("keyword 超过 50 字符 → INVALID_REQUEST；恰好 50 字符放行")
    void rejectsOverlongKeyword() {
        addArticle(1L, 2L, "标题");

        assertThat(service.list(NewsQuery.of(
                        Set.of(), null, null, null, null, null, "字".repeat(50), null, null))
                .items())
                .isEmpty();
        assertThatThrownBy(() -> service.list(NewsQuery.of(
                        Set.of(), null, null, null, null, null, "字".repeat(51), null, null)))
                .isInstanceOf(InvalidNewsQueryException.class)
                .hasMessageContaining("keyword");
    }

    @Test
    @DisplayName("全空白 keyword 视为不筛选，而不是筛选一个空白词")
    void treatsBlankKeywordAsNoFilter() {
        addArticle(1L, 2L, "标题");

        assertThat(service.list(NewsQuery.of(
                        Set.of(), null, null, null, null, null, "   ", null, null))
                .items())
                .hasSize(1);
    }
}
