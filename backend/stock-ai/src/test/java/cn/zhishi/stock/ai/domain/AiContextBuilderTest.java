package cn.zhishi.stock.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.news.domain.NewsEvidence;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AiContextBuilder} 的固化逻辑测试。
 *
 * <p>它守着一条不会报错的缺陷形态：**上下文不冻结**。若构建器把"此刻的行情"
 * 而不是"任务开始时的行情"写进快照，一份 30 秒后才生成完的报告会引用生成时刻的价格，
 * 而正文里写的是分析区间——两者对不上，且没有任何测试会因此变红。
 *
 * <p>另外守住引用安全的**结构性保证**：{@link AiEvidenceCandidate} 有 {@code sourceUrl}，
 * 而它转出的 {@link LlmEvidence} 没有——模型连原文地址都没见过，
 * 因此不可能"生成一个可信 URL"。
 */
class AiContextBuilderTest {

    private static final String SECURITY_ID = "sim-600519";

    private StubQuoteBatchProvider quotes = new StubQuoteBatchProvider();
    private StubSectorProvider sectors = new StubSectorProvider();
    private StubNewsEvidenceProvider news = new StubNewsEvidenceProvider();
    private MarketOverview overview;
    private int newsLimit = 20;

    private AiContextBuilder builder() {
        return new AiContextBuilder(
                quotes, overviewService(), sectors, news, AiFixtures.hasher(), newsLimit);
    }

    private MarketOverviewQueryService overviewService() {
        MarketOverviewStore store = overview == null
                ? marketCode -> Optional.empty()
                : marketCode -> Optional.of(overview);
        MarketOverviewArchive archive = marketCode -> Optional.empty();
        return new MarketOverviewQueryService(store, archive);
    }

    private static List<AiContextTarget> targets(AiContextTarget... items) {
        return List.of(items);
    }

    // ---------- 证券目标 ----------

    @Test
    @DisplayName("证券目标产出 QUOTE 快照与 QUOTE 证据，核心行情齐备")
    void securityTargetProducesQuoteSnapshotAndEvidence() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.coreDataAvailable()).isTrue();
        // 本用例没有资讯，因此会有一条"资讯缺失、报告受限"的降级说明；
        // 这里断言的是"没有核心行情类降级"
        assertThat(result.limitations())
                .singleElement()
                .asString()
                .contains("资讯");
        assertThat(result.availableCategories()).containsExactly(AiContextType.QUOTE);
        assertThat(result.evidenceCandidates())
                .extracting(AiEvidenceCandidate::evidenceType)
                .containsExactly(AiEvidenceType.QUOTE);
    }

    @Test
    @DisplayName("QUOTE 快照的截止时间取自行情批次的数据时间，而不是「现在」")
    void quoteCutoffComesFromBatchDataTime() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.dataCutoffs())
                .singleElement()
                .satisfies(cutoff -> {
                    assertThat(cutoff.category()).isEqualTo(AiContextType.QUOTE);
                    assertThat(cutoff.dataCutoffAt()).isEqualTo(AiFixtures.DATA_TIME);
                });
    }

    @Test
    @DisplayName("快照内容含对外标识与行情字段，供后续渲染 Prompt 使用")
    void quoteSnapshotCarriesDisplayFields() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        Map<String, Object> data = builder().build(
                        targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null)
                .snapshots().get(0).contextData();

        assertThat(data)
                .containsEntry("securityId", SECURITY_ID)
                .containsEntry("securityCode", "600519")
                .containsEntry("securityName", "模拟证券600519")
                .containsEntry("latestPrice", "10.80")
                .containsEntry("changeRate", "0.0800");
    }

    @Test
    @DisplayName("证券在当前批次里没有快照 → 核心行情不可用，且不产出 QUOTE 快照")
    void missingQuoteMarksCoreDataUnavailable() {
        // 批次为空
        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.coreDataAvailable()).isFalse();
        assertThat(result.limitations()).isNotEmpty();
        assertThat(result.snapshots()).isEmpty();
        assertThat(result.evidenceCandidates()).isEmpty();
    }

    @Test
    @DisplayName("多标的对比时，任一标的缺行情即标记核心行情不可用，但其余标的仍被固化")
    void partialQuoteBatchDegradesWithoutDiscardingOthers() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextBuildResult result = builder().build(
                targets(
                        AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY),
                        AiFixtures.securityTarget("000001", AiTargetRole.COMPARISON)),
                null,
                null);

        assertThat(result.coreDataAvailable()).isFalse();
        // 1 条"缺少快照" + 2 条"没有可用资讯"（两个标的各一条）
        assertThat(result.limitations()).hasSize(3);
        assertThat(result.limitations()).anyMatch(text -> text.contains("缺少快照"));
        // 有行情的那个标的仍被固化——降级不丢弃可用数据，只是明确告知不完整
        assertThat(result.snapshots())
                .extracting(AiContextSnapshot::sourceKey)
                .containsExactly(SECURITY_ID);
    }

    // ---------- 资讯 ----------

    @Test
    @DisplayName("每条资讯产出一个证据候选，同一目标共用一条 NEWS 快照")
    void newsProducesSnapshotAndOneEvidencePerItem() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(
                AiFixtures.news(1001, "标题一", AiFixtures.PUBLISHED),
                AiFixtures.news(1002, "标题二", AiFixtures.PUBLISHED.minusHours(2))));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.availableCategories())
                .containsExactly(AiContextType.QUOTE, AiContextType.NEWS);
        assertThat(result.snapshots())
                .filteredOn(snapshot -> snapshot.contextType() == AiContextType.NEWS)
                .hasSize(1);
        assertThat(result.evidenceCandidates())
                .filteredOn(evidence -> evidence.evidenceType() == AiEvidenceType.NEWS)
                .hasSize(2);
    }

    @Test
    @DisplayName("公告按类型映射为 ANNOUNCEMENT 证据，不混进 NEWS")
    void announcementsMapToAnnouncementEvidenceType() {
        news.put(SECURITY_ID, List.of(
                AiFixtures.news(1001, "媒体报道", AiFixtures.PUBLISHED),
                AiFixtures.announcement(1002, "公司公告", AiFixtures.PUBLISHED)));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.evidenceCandidates())
                .extracting(AiEvidenceCandidate::evidenceType)
                .containsExactlyInAnyOrder(AiEvidenceType.NEWS, AiEvidenceType.ANNOUNCEMENT);
    }

    @Test
    @DisplayName("没有资讯 → 不产出 NEWS 快照，并记下降级说明（受限分析，不是失败）")
    void noNewsProducesLimitationAndNoNewsSnapshot() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.availableCategories()).containsExactly(AiContextType.QUOTE);
        assertThat(result.snapshots())
                .noneMatch(snapshot -> snapshot.contextType() == AiContextType.NEWS);
        assertThat(result.limitations()).isNotEmpty();
        // 资讯缺失不阻断任务：契约 §13.5 允许产生 LIMITED 报告
        assertThat(result.coreDataAvailable()).isTrue();
    }

    @Test
    @DisplayName("NEWS 快照的截止时间取该目标最新一条资讯的发布时间，不编造「现在」")
    void newsCutoffComesFromLatestPublishedAt() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(
                AiFixtures.news(1001, "较早", AiFixtures.PUBLISHED.minusHours(2)),
                AiFixtures.news(1002, "最新", AiFixtures.PUBLISHED)));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.dataCutoffs())
                .filteredOn(cutoff -> cutoff.category() == AiContextType.NEWS)
                .singleElement()
                .satisfies(cutoff -> assertThat(cutoff.dataCutoffAt()).isEqualTo(AiFixtures.PUBLISHED));
    }

    @Test
    @DisplayName("资讯条数上限透传给资讯域，不在 AI 侧再截一次")
    void newsLimitIsPassedToProvider() {
        newsLimit = 3;
        news.put(SECURITY_ID, List.of(AiFixtures.news(1001, "标题", AiFixtures.PUBLISHED)));

        builder().build(targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(news.lastLimit).isEqualTo(3);
    }

    @Test
    @DisplayName("分析区间原样传给资讯域，AI 侧不自己过滤发布时间")
    void analysisRangeIsPassedToProvider() {
        OffsetDateTime start = AiFixtures.PUBLISHED.minusDays(5);
        OffsetDateTime end = AiFixtures.PUBLISHED;

        builder().build(targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), start, end);

        assertThat(news.lastStartAt).isEqualTo(start);
        assertThat(news.lastEndAt).isEqualTo(end);
    }

    // ---------- 板块与市场 ----------

    @Test
    @DisplayName("板块目标产出 SECTOR 快照，含成分股关系")
    void sectorTargetProducesSectorSnapshot() {
        // 板块上下文的截止时间取自行情批次（板块主数据自身没有时间戳），因此批次必须存在
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        sectors.add(AiFixtures.sector("sim-bk0011", "BK11", "半导体"), List.of(
                AiFixtures.member(SECURITY_ID, "sim-bk0011", true),
                AiFixtures.member("sim-000001", "sim-bk0011", false)));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.sectorTarget("sim-bk0011", "BK11", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.availableCategories()).containsExactly(AiContextType.SECTOR);
        Map<String, Object> data = result.snapshots().get(0).contextData();
        assertThat(data).containsEntry("sectorId", "sim-bk0011").containsEntry("sectorName", "半导体");
        assertThat(data).containsEntry("memberCount", 2);
        assertThat((List<?>) data.get("members")).hasSize(2);
        assertThat(result.dataCutoffs().get(0).dataCutoffAt()).isEqualTo(AiFixtures.DATA_TIME);
    }

    @Test
    @DisplayName("行情批次缺失时板块上下文不固化——没有批次就没有有意义的截止时间")
    void sectorWithoutQuoteBatchIsNotFrozen() {
        sectors.add(AiFixtures.sector("sim-bk0011", "BK11", "半导体"), List.of());

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.sectorTarget("sim-bk0011", "BK11", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.availableCategories()).isEmpty();
        assertThat(result.coreDataAvailable()).isFalse();
        assertThat(result.limitations()).anyMatch(text -> text.contains("行情批次缺失"));
    }

    @Test
    @DisplayName("板块不在主数据里时记降级，不抛异常")
    void unknownSectorDegrades() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextBuildResult result = builder().build(
                targets(AiFixtures.sectorTarget("sim-bk9999", "BK99", AiTargetRole.PRIMARY)), null, null);

        assertThat(result.coreDataAvailable()).isFalse();
        assertThat(result.limitations()).anyMatch(text -> text.contains("不在当前板块主数据中"));
    }

    @Test
    @DisplayName("市场目标产出 QUOTE 快照，内容来自总览口径而不是自己重算")
    void marketTargetProducesQuoteSnapshotFromOverview() {
        overview = AiFixtures.overview(AiFixtures.DATA_TIME);

        AiContextBuildResult result = builder().build(targets(AiFixtures.marketTarget()), null, null);

        assertThat(result.coreDataAvailable()).isTrue();
        assertThat(result.availableCategories()).containsExactly(AiContextType.QUOTE);
        Map<String, Object> data = result.snapshots().get(0).contextData();
        assertThat(data)
                .containsEntry("marketCode", AiFixtures.MARKET_CODE)
                .containsEntry("snapshotVersion", "seq-20260918");
        assertThat(result.dataCutoffs().get(0).dataCutoffAt()).isEqualTo(AiFixtures.DATA_TIME);
    }

    @Test
    @DisplayName("市场总览不可用 → 核心行情不可用，而不是补一个空行情")
    void unavailableOverviewMarksCoreDataUnavailable() {
        overview = null;

        AiContextBuildResult result = builder().build(targets(AiFixtures.marketTarget()), null, null);

        assertThat(result.coreDataAvailable()).isFalse();
        assertThat(result.limitations()).isNotEmpty();
        assertThat(result.snapshots()).isEmpty();
    }

    // ---------- 编号连续性 ----------

    @Test
    @DisplayName("快照编号从 1 连续递增，证据编号从 1 连续递增")
    void numberingIsSequentialFromOne() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        quotes.add(AiFixtures.quote("000001", "模拟证券000001", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(
                AiFixtures.news(1001, "标题一", AiFixtures.PUBLISHED),
                AiFixtures.news(1002, "标题二", AiFixtures.PUBLISHED)));
        news.put("sim-000001", List.of(AiFixtures.news(1003, "标题三", AiFixtures.PUBLISHED)));

        AiContextBuildResult result = builder().build(
                targets(
                        AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY),
                        AiFixtures.securityTarget("000001", AiTargetRole.COMPARISON)),
                null,
                null);

        assertThat(result.snapshots())
                .extracting(AiContextSnapshot::snapshotNo)
                .containsExactly(1, 2, 3, 4);
        // 证据：600519 的 QUOTE + 2 条资讯，000001 的 QUOTE + 1 条资讯 = 5 条
        assertThat(result.evidenceCandidates())
                .extracting(AiEvidenceCandidate::evidenceNo)
                .containsExactly(1, 2, 3, 4, 5);
    }

    // ---------- 哈希 ----------

    @Test
    @DisplayName("同一输入两次构建产出逐位相同的哈希——冻结必须是确定性的")
    void contentHashIsDeterministic() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextBuildResult first = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);
        AiContextBuildResult second = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(first.snapshots().get(0).contentHash())
                .isEqualTo(second.snapshots().get(0).contentHash());
        assertThat(first.evidenceCandidates().get(0).contentHash())
                .isEqualTo(second.evidenceCandidates().get(0).contentHash());
    }

    @Test
    @DisplayName("内容变化时哈希随之变化——否则「同一份事实」的判定会永远为真")
    void contentHashChangesWithContent() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        AiContextBuildResult baseline = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        quotes.reset();
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME.plusDays(1)));
        AiContextBuildResult changed = builder().build(
                targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null);

        assertThat(changed.snapshots().get(0).contentHash())
                .isNotEqualTo(baseline.snapshots().get(0).contentHash());
    }

    // ---------- 引用安全 ----------

    @Test
    @DisplayName("证据候选带原文地址，但转成模型视图后地址消失——模型不可能生成可信 URL")
    void evidenceCandidateCarriesUrlButModelViewDoesNot() {
        news.put(SECURITY_ID, List.of(AiFixtures.news(1001, "标题", AiFixtures.PUBLISHED)));

        AiEvidenceCandidate candidate = builder().build(
                        targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null)
                .evidenceCandidates().get(0);

        assertThat(candidate.sourceUrl()).isNotBlank();
        assertThat(candidate.toLlmEvidence().sourceTitle()).isEqualTo(candidate.sourceTitle());
        assertThat(candidate.toLlmEvidence().evidenceNo()).isEqualTo(candidate.evidenceNo());
        // LlmEvidence 类型上根本没有 url 字段——结构性保证，不靠校验器
        assertThat(LlmEvidence.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("sourceUrl", "url", "originalUrl", "link");
    }

    @Test
    @DisplayName("证据摘要与标题来自资讯域，不在 AI 侧改写")
    void evidenceSummaryComesFromNewsDomain() {
        news.put(SECURITY_ID, List.of(AiFixtures.news(1001, "原始标题", AiFixtures.PUBLISHED)));

        AiEvidenceCandidate candidate = builder().build(
                        targets(AiFixtures.securityTarget("600519", AiTargetRole.PRIMARY)), null, null)
                .evidenceCandidates().get(0);

        assertThat(candidate.sourceTitle()).isEqualTo("原始标题");
        assertThat(candidate.evidenceSummary()).isEqualTo("摘要1001");
        assertThat(candidate.sourcePublishedAt()).isEqualTo(AiFixtures.PUBLISHED);
        assertThat(candidate.accessStatus()).isEqualTo(AiEvidenceAccessStatus.AVAILABLE);
    }

    // ---------- 桩 ----------

    private static final class StubQuoteBatchProvider implements QuoteSnapshotBatchProvider {
        private final List<QuoteSnapshot> snapshots = new ArrayList<>();

        void add(QuoteSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        void reset() {
            snapshots.clear();
        }

        @Override
        public List<QuoteSnapshot> fetchBatch(String marketCode) {
            return List.copyOf(snapshots);
        }
    }

    private static final class StubSectorProvider implements SectorProvider {
        private final List<Sector> sectors = new ArrayList<>();
        private final Map<String, List<SectorMember>> memberships = new LinkedHashMap<>();

        void add(Sector sector, List<SectorMember> members) {
            sectors.add(sector);
            memberships.put(sector.sectorId(), members);
        }

        @Override
        public List<Sector> findAll(String marketCode) {
            return List.copyOf(sectors);
        }

        @Override
        public Map<String, List<SectorMember>> memberships(String marketCode, LocalDate effectiveDate) {
            return Map.copyOf(memberships);
        }
    }

    private static final class StubNewsEvidenceProvider implements NewsEvidenceProvider {
        private final Map<String, List<NewsEvidence>> byTarget = new LinkedHashMap<>();
        private int lastLimit;
        private OffsetDateTime lastStartAt;
        private OffsetDateTime lastEndAt;

        void put(String targetId, List<NewsEvidence> evidence) {
            byTarget.put(targetId, evidence);
        }

        @Override
        public List<NewsEvidence> evidenceFor(
                NewsTargetType targetType,
                String targetId,
                OffsetDateTime startAt,
                OffsetDateTime endAt,
                int limit) {
            lastLimit = limit;
            lastStartAt = startAt;
            lastEndAt = endAt;
            List<NewsEvidence> found = byTarget.getOrDefault(targetId, List.of());
            return found.size() <= limit ? found : found.subList(0, limit);
        }
    }
}
