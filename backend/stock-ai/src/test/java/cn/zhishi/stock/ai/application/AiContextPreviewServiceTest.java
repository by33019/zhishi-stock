package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiContextType;
import cn.zhishi.stock.ai.domain.AiFixtures;
import cn.zhishi.stock.ai.domain.AiSceneCatalog;
import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.Sector;
import cn.zhishi.stock.market.domain.SectorIdentity;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorMember;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SecurityIdentity;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.domain.NewsEvidence;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsTargetType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI-02 上下文预览用例测试。
 *
 * <p>它守着两类**不会报错**的缺陷：
 *
 * <ol>
 *   <li><b>规则悄悄放宽</b>：目标数量、角色、类型、区间上限若在校验里漏掉一条，
 *       请求照样成功，只是任务在 AI-03 落库时才撞唯一索引或 CHECK 约束——
 *       或者更糟，撞不上，于是一个非法目标被静默接受。
 *   <li><b>为未接入的数据补一个"看起来合法的截止时间"</b>：{@code dataCategories}
 *       若把 K 线、主营业务也列上，用户会以为它们参与了分析，而实际没有。
 * </ol>
 *
 * <p>资讯"只算允许 AI 的来源"这条判据**不在本层**——它由资讯域的
 * {@code NewsQueryService.evidenceFor} 保证（见 {@code NewsEvidenceQueryTest}）。
 * 本层断言的是"条数等于实际拿到的证据条数"，不去重算一遍可见性。
 */
class AiContextPreviewServiceTest {

    private static final String SECURITY_ID = "sim-600519";
    private static final String OTHER_SECURITY_ID = "sim-000001";
    private static final String SECTOR_ID = "sim-bk0011";

    private final AiSceneCatalog catalog = new AiSceneCatalog();
    private final StubQuoteBatchProvider quotes = new StubQuoteBatchProvider();
    private final StubSectorProvider sectors = new StubSectorProvider();
    private final StubNewsEvidenceProvider news = new StubNewsEvidenceProvider();
    private final StubSecurityIdentities securities = new StubSecurityIdentities();
    private final StubSectorIdentities sectorIdentities = new StubSectorIdentities();
    private MarketOverview overview;
    private int newsLimit = 20;

    /**
     * 主数据只注册这几只证券与一个板块。
     *
     * <p>刻意**不**给"未注册标识"留兜底：{@code unresolvableSecurityIsRejected} 靠的正是
     * 主数据里确实没有 {@code sim-999999}。若桩对任何入参都返回一个身份，
     * 那条测试就会永远为真而毫无价值。
     */
    @BeforeEach
    void registerMasterData() {
        securities.put("600519", "模拟证券600519");
        securities.put("000001", "模拟证券000001");
        securities.put("600036", "模拟证券600036");
        securities.put("601318", "模拟证券601318");
        sectorIdentities.put(SECTOR_ID, "BK11", "半导体");
    }

    private AiContextPreviewService service() {
        AiContextBuilder builder = new AiContextBuilder(
                quotes, overviewService(), sectors, news, AiFixtures.hasher(), newsLimit);
        return new AiContextPreviewService(catalog, builder, securities, sectorIdentities);
    }

    private MarketOverviewQueryService overviewService() {
        MarketOverviewStore store = overview == null
                ? marketCode -> Optional.empty()
                : marketCode -> Optional.of(overview);
        MarketOverviewArchive archive = marketCode -> Optional.empty();
        return new MarketOverviewQueryService(store, archive);
    }

    // ---------- 场景解析 ----------

    @Test
    @DisplayName("未知场景码 → 400，而不是静默落到某个默认场景")
    void unknownSceneIsRejected() {
        assertThatThrownBy(() -> service().preview(request("NOT_A_SCENE", market())))
                .isInstanceOf(InvalidAiContextQueryException.class)
                .hasMessageContaining("NOT_A_SCENE");
    }

    @Test
    @DisplayName("场景码为空 → 400")
    void blankSceneIsRejected() {
        assertThatThrownBy(() -> service().preview(request(null, market())))
                .isInstanceOf(InvalidAiContextQueryException.class);
    }

    // ---------- 目标数量与类型 ----------

    @Test
    @DisplayName("MARKET 场景接受单个 CN 市场目标，并回填市场代码作为摘要")
    void marketSceneAcceptsSingleMarketTarget() {
        overview = AiFixtures.overview(AiFixtures.DATA_TIME);

        AiContextPreview preview = service().preview(request("MARKET", market()));

        assertThat(preview.targets()).singleElement().satisfies(target -> {
            assertThat(target.targetType()).isEqualTo(AiTargetType.MARKET);
            assertThat(target.targetId()).isEqualTo(AiFixtures.MARKET_CODE);
            assertThat(target.targetCode()).isEqualTo(AiFixtures.MARKET_CODE);
            assertThat(target.targetRole()).isEqualTo(AiTargetRole.PRIMARY);
        });
    }

    @Test
    @DisplayName("MARKET 场景拒绝证券目标——目标类型不在场景白名单里")
    void marketSceneRejectsSecurityTarget() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        assertTargetInvalid(() -> service().preview(request("MARKET", security("600519"))));
    }

    @Test
    @DisplayName("市场代码不在白名单 → 400，而不是当成一个未知市场去取数")
    void unknownMarketCodeIsRejected() {
        assertTargetInvalid(() -> service()
                .preview(request("MARKET", new AiTargetRequest("MARKET", "US", "PRIMARY"))));
    }

    @Test
    @DisplayName("STOCK 场景拒绝两个目标——单标的场景最多一个")
    void stockSceneRejectsTwoTargets() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        assertTargetInvalid(() -> service().preview(request(
                "STOCK", security("600519"), new AiTargetRequest("SECURITY", OTHER_SECURITY_ID, "COMPARISON"))));
    }

    @Test
    @DisplayName("目标数组为空 → 400（低于 minTargets）")
    void emptyTargetsIsRejected() {
        assertTargetInvalid(() -> service().preview(request("MARKET")));
    }

    @Test
    @DisplayName("目标数组为 null → 400，而不是当成「没有目标」继续往下走")
    void nullTargetsIsRejected() {
        assertTargetInvalid(() -> service()
                .preview(new AiContextPreviewRequest("MARKET", null, null, null)));
    }

    @Test
    @DisplayName("COMPARE 场景接受 2 个目标，主目标在前、对比目标在后")
    void compareSceneAcceptsTwoTargets() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        quotes.add(AiFixtures.quote("000001", "模拟证券000001", AiFixtures.DATA_TIME));

        AiContextPreview preview = service().preview(request(
                "COMPARE", security("600519"), new AiTargetRequest("SECURITY", OTHER_SECURITY_ID, "COMPARISON")));

        assertThat(preview.targets())
                .extracting(AiContextTarget::targetRole)
                .containsExactly(AiTargetRole.PRIMARY, AiTargetRole.COMPARISON);
    }

    @Test
    @DisplayName("COMPARE 场景只有 1 个目标 → 400（低于 minTargets=2）")
    void compareSceneRejectsSingleTarget() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        assertTargetInvalid(() -> service().preview(request("COMPARE", security("600519"))));
    }

    @Test
    @DisplayName("COMPARE 场景 4 个目标 → 400（高于 maxTargets=3）")
    void compareSceneRejectsFourTargets() {
        assertTargetInvalid(() -> service().preview(request(
                "COMPARE",
                security("600519"),
                new AiTargetRequest("SECURITY", OTHER_SECURITY_ID, "COMPARISON"),
                new AiTargetRequest("SECURITY", "sim-600036", "COMPARISON"),
                new AiTargetRequest("SECURITY", "sim-601318", "COMPARISON"))));
    }

    @Test
    @DisplayName("COMPARE 场景没有 PRIMARY → 400，而不是把第一个目标默默当成主目标")
    void compareSceneRequiresOnePrimary() {
        assertTargetInvalid(() -> service().preview(request(
                "COMPARE",
                new AiTargetRequest("SECURITY", SECURITY_ID, "COMPARISON"),
                new AiTargetRequest("SECURITY", OTHER_SECURITY_ID, "COMPARISON"))));
    }

    @Test
    @DisplayName("COMPARE 场景出现两个 PRIMARY → 400")
    void compareSceneRejectsTwoPrimaries() {
        assertTargetInvalid(() -> service().preview(request(
                "COMPARE",
                security("600519"),
                new AiTargetRequest("SECURITY", OTHER_SECURITY_ID, "PRIMARY"))));
    }

    @Test
    @DisplayName("单标的场景把唯一目标标成 COMPARISON → 400（缺 PRIMARY）")
    void stockSceneRequiresPrimaryRole() {
        assertTargetInvalid(() -> service().preview(request(
                "STOCK", new AiTargetRequest("SECURITY", SECURITY_ID, "COMPARISON"))));
    }

    @Test
    @DisplayName("客户端传 CONTEXT 角色 → 400：该角色仅允许服务端生成（契约 §13.1）")
    void contextRoleIsRejectedFromClient() {
        assertTargetInvalid(() -> service().preview(request(
                "STOCK", new AiTargetRequest("SECURITY", SECURITY_ID, "CONTEXT"))));
    }

    @Test
    @DisplayName("未知目标类型码 → 400")
    void unknownTargetTypeIsRejected() {
        assertTargetInvalid(() -> service().preview(request(
                "STOCK", new AiTargetRequest("STOCK", SECURITY_ID, "PRIMARY"))));
    }

    @Test
    @DisplayName("未知目标角色码 → 400")
    void unknownTargetRoleIsRejected() {
        assertTargetInvalid(() -> service().preview(request(
                "STOCK", new AiTargetRequest("SECURITY", SECURITY_ID, "MAIN"))));
    }

    @Test
    @DisplayName("目标类型或角色为空 → 400")
    void nullTargetTypeOrRoleIsRejected() {
        assertTargetInvalid(() -> service()
                .preview(request("STOCK", new AiTargetRequest(null, SECURITY_ID, "PRIMARY"))));
        assertTargetInvalid(() -> service()
                .preview(request("STOCK", new AiTargetRequest("SECURITY", SECURITY_ID, null))));
    }

    @Test
    @DisplayName("同一目标重复出现 → 400（V6 的 uk_ai_task_target 会撞）")
    void duplicateTargetIsRejected() {
        // 角色一主一辅，好让本用例只剩「重复」这一个违规条件——
        // 两个 PRIMARY 会先撞上主目标唯一性，那样测的就不是重复了
        assertTargetInvalid(() -> service().preview(request(
                "COMPARE",
                security("600519"),
                new AiTargetRequest("SECURITY", SECURITY_ID, "COMPARISON"))));
    }

    // ---------- 目标主数据解析 ----------

    @Test
    @DisplayName("证券标识解析不到主数据 → 400，而不是带着一个空名进上下文")
    void unresolvableSecurityIsRejected() {
        assertTargetInvalid(() -> service()
                .preview(request("STOCK", new AiTargetRequest("SECURITY", "sim-999999", "PRIMARY"))));
    }

    @Test
    @DisplayName("板块标识解析不到主数据 → 400")
    void unresolvableSectorIsRejected() {
        assertTargetInvalid(() -> service()
                .preview(request("SECTOR", new AiTargetRequest("SECTOR", "sim-bk9999", "PRIMARY"))));
    }

    @Test
    @DisplayName("证券摘要取自主数据：代码与名称都是主数据的快照，不是请求里的字符串")
    void resolvedSecurityCarriesMasterDataSummary() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.targets()).singleElement().satisfies(target -> {
            assertThat(target.targetId()).isEqualTo(SECURITY_ID);
            assertThat(target.targetCode()).isEqualTo("600519");
            assertThat(target.targetName()).isEqualTo("模拟证券600519");
        });
    }

    @Test
    @DisplayName("SECTOR 场景接受板块目标，摘要取自板块主数据")
    void sectorSceneAcceptsSectorTarget() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        sectors.add(AiFixtures.sector(SECTOR_ID, "BK11", "半导体"), List.of(
                AiFixtures.member(SECURITY_ID, SECTOR_ID, true)));

        AiContextPreview preview = service().preview(
                request("SECTOR", new AiTargetRequest("SECTOR", SECTOR_ID, "PRIMARY")));

        assertThat(preview.targets()).singleElement().satisfies(target -> {
            assertThat(target.targetType()).isEqualTo(AiTargetType.SECTOR);
            assertThat(target.targetCode()).isEqualTo("BK11");
            assertThat(target.targetName()).isEqualTo("半导体");
        });
    }

    // ---------- 区间校验 ----------

    @Test
    @DisplayName("只给区间起点 → 400：半截区间的语义没有定义，猜一个就是编造")
    void onlyStartAtIsRejected() {
        assertThatThrownBy(() -> service().preview(new AiContextPreviewRequest(
                        "MARKET", List.of(market()), AiFixtures.DATA_TIME.minusDays(5), null)))
                .isInstanceOf(InvalidAiContextQueryException.class);
    }

    @Test
    @DisplayName("只给区间终点 → 400")
    void onlyEndAtIsRejected() {
        assertThatThrownBy(() -> service().preview(new AiContextPreviewRequest(
                        "MARKET", List.of(market()), null, AiFixtures.DATA_TIME)))
                .isInstanceOf(InvalidAiContextQueryException.class);
    }

    @Test
    @DisplayName("终点早于起点 → 400")
    void endBeforeStartIsRejected() {
        assertThatThrownBy(() -> service().preview(new AiContextPreviewRequest(
                        "MARKET",
                        List.of(market()),
                        AiFixtures.DATA_TIME,
                        AiFixtures.DATA_TIME.minusDays(1))))
                .isInstanceOf(InvalidAiContextQueryException.class);
    }

    @Test
    @DisplayName("跨度超过 365 天 → 400（PRD：自定义范围最长 1 年）")
    void spanBeyondMaxCustomDaysIsRejected() {
        assertThatThrownBy(() -> service().preview(new AiContextPreviewRequest(
                        "MARKET",
                        List.of(market()),
                        AiFixtures.DATA_TIME.minusDays(366),
                        AiFixtures.DATA_TIME)))
                .isInstanceOf(InvalidAiContextQueryException.class)
                .hasMessageContaining("365");
    }

    @Test
    @DisplayName("跨度正好 365 天 → 接受，边界不多不少")
    void spanAtMaxCustomDaysIsAccepted() {
        overview = AiFixtures.overview(AiFixtures.DATA_TIME);

        AiContextPreview preview = service().preview(new AiContextPreviewRequest(
                "MARKET",
                List.of(market()),
                AiFixtures.DATA_TIME.minusDays(365),
                AiFixtures.DATA_TIME));

        assertThat(preview.canGenerate()).isTrue();
    }

    @Test
    @DisplayName("区间原样透传给上下文构建器，不在用例层自行收缩")
    void analysisRangeIsPassedToBuilder() {
        OffsetDateTime start = AiFixtures.PUBLISHED.minusDays(3);
        OffsetDateTime end = AiFixtures.PUBLISHED;

        service().preview(new AiContextPreviewRequest(
                "STOCK", List.of(security("600519")), start, end));

        assertThat(news.lastStartAt).isEqualTo(start);
        assertThat(news.lastEndAt).isEqualTo(end);
    }

    // ---------- 数据类别与可生成性 ----------

    @Test
    @DisplayName("dataCategories 只列实际取到的类别，不为未接入的类别补截止时间")
    void dataCategoriesExcludeUnavailableCategories() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(AiFixtures.news(1001, "标题", AiFixtures.PUBLISHED)));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.dataCategories())
                .extracting(cutoff -> cutoff.category())
                .containsExactly(AiContextType.QUOTE, AiContextType.NEWS);
        assertThat(preview.dataCategories())
                .noneMatch(cutoff -> cutoff.category() == AiContextType.KLINE
                        || cutoff.category() == AiContextType.BUSINESS
                        || cutoff.category() == AiContextType.CALENDAR
                        || cutoff.category() == AiContextType.RULE);
    }

    @Test
    @DisplayName("截止时间来自真实取数时刻，不是「现在」")
    void cutoffComesFromDataNotFromClock() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(AiFixtures.news(1001, "标题", AiFixtures.PUBLISHED)));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.dataCategories())
                .extracting(cutoff -> cutoff.dataCutoffAt())
                .containsExactly(AiFixtures.DATA_TIME, AiFixtures.PUBLISHED);
    }

    @Test
    @DisplayName("核心行情齐备 → canGenerate=true")
    void canGenerateTrueWhenCoreDataAvailable() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.canGenerate()).isTrue();
    }

    @Test
    @DisplayName("核心行情缺失 → canGenerate=false 且给出说明，而不是抛 400")
    void canGenerateFalseWhenCoreDataMissing() {
        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.canGenerate()).isFalse();
        assertThat(preview.limitations()).anyMatch(text -> text.contains("缺少快照"));
    }

    @Test
    @DisplayName("资讯为空不阻断生成：canGenerate=true，但 limitations 说明报告受限")
    void missingNewsKeepsGenerationPossibleButLimited() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.canGenerate()).isTrue();
        assertThat(preview.newsCount()).isZero();
        assertThat(preview.limitations()).anyMatch(text -> text.contains("资讯"));
    }

    // ---------- 资讯条数 ----------

    @Test
    @DisplayName("newsCount 等于实际进入上下文的资讯证据条数，行情与板块证据不计入")
    void newsCountCountsOnlyNewsEvidence() {
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(
                AiFixtures.news(1001, "标题一", AiFixtures.PUBLISHED),
                AiFixtures.news(1002, "标题二", AiFixtures.PUBLISHED),
                AiFixtures.announcement(1003, "公告", AiFixtures.PUBLISHED)));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(preview.newsCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("资讯条数上限透传到资讯域，不在预览层另截一次")
    void newsLimitIsPassedThrough() {
        newsLimit = 2;
        quotes.add(AiFixtures.quote("600519", "模拟证券600519", AiFixtures.DATA_TIME));
        news.put(SECURITY_ID, List.of(
                AiFixtures.news(1001, "标题一", AiFixtures.PUBLISHED),
                AiFixtures.news(1002, "标题二", AiFixtures.PUBLISHED),
                AiFixtures.news(1003, "标题三", AiFixtures.PUBLISHED)));

        AiContextPreview preview = service().preview(request("STOCK", security("600519")));

        assertThat(news.lastLimit).isEqualTo(2);
        assertThat(preview.newsCount()).isEqualTo(2);
    }

    // ---------- 引用安全 ----------

    @Test
    @DisplayName("响应类型上不存在内部 Prompt 字段——结构性保证，不靠人工过滤")
    void previewCarriesNoInternalPrompt() {
        assertThat(AiContextPreview.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("systemPrompt", "userPrompt", "prompt", "rawContext");
    }

    @Test
    @DisplayName("预览不返回证据正文：报告尚未生成，此时给出候选会让前端以为已有引用")
    void previewCarriesNoEvidenceBodies() {
        assertThat(AiContextPreview.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("evidence", "evidenceCandidates", "snapshots");
    }

    // ---------- 辅助 ----------

    private static AiTargetRequest market() {
        return new AiTargetRequest("MARKET", AiFixtures.MARKET_CODE, "PRIMARY");
    }

    private static AiTargetRequest security(String code) {
        return new AiTargetRequest("SECURITY", "sim-" + code, "PRIMARY");
    }

    private static AiContextPreviewRequest request(String scene, AiTargetRequest... targets) {
        return new AiContextPreviewRequest(scene, List.of(targets), null, null);
    }

    private static void assertTargetInvalid(ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(InvalidAiTargetException.class)
                .satisfies(exception -> assertThat(((InvalidAiTargetException) exception).code())
                        .isEqualTo("AI_TARGET_INVALID"));
    }

    // ---------- 桩 ----------

    private static final class StubQuoteBatchProvider implements QuoteSnapshotBatchProvider {
        private final List<QuoteSnapshot> snapshots = new ArrayList<>();

        void add(QuoteSnapshot snapshot) {
            snapshots.add(snapshot);
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

    private static final class StubSecurityIdentities implements SecurityIdentityProvider {
        private final Map<String, SecurityIdentity> byId = new LinkedHashMap<>();

        void put(String code, String name) {
            byId.put("sim-" + code, new SecurityIdentity(Long.parseLong(code), AiFixtures.security(code, name)));
        }

        @Override
        public Optional<SecurityIdentity> resolve(String securityId) {
            return Optional.ofNullable(byId.get(securityId));
        }

        @Override
        public Map<String, SecurityIdentity> resolveAll(Collection<String> securityIds) {
            Map<String, SecurityIdentity> found = new LinkedHashMap<>();
            for (String securityId : securityIds) {
                SecurityIdentity identity = byId.get(securityId);
                if (identity != null) {
                    found.put(securityId, identity);
                }
            }
            return found;
        }

        @Override
        public Map<Long, SecurityIdentity> findByStorageIds(Collection<Long> storageIds) {
            return Map.of();
        }
    }

    private static final class StubSectorIdentities implements SectorIdentityProvider {
        private final Map<String, SectorIdentity> byId = new LinkedHashMap<>();

        void put(String sectorId, String code, String name) {
            byId.put(sectorId, new SectorIdentity(
                    (long) sectorId.hashCode(), AiFixtures.sector(sectorId, code, name)));
        }

        @Override
        public Optional<SectorIdentity> resolve(String sectorId) {
            return Optional.ofNullable(byId.get(sectorId));
        }

        @Override
        public Map<String, SectorIdentity> resolveAll(Collection<String> sectorIds) {
            Map<String, SectorIdentity> found = new LinkedHashMap<>();
            for (String sectorId : sectorIds) {
                SectorIdentity identity = byId.get(sectorId);
                if (identity != null) {
                    found.put(sectorId, identity);
                }
            }
            return found;
        }

        @Override
        public Map<Long, SectorIdentity> findByStorageIds(Collection<Long> storageIds) {
            return Map.of();
        }
    }
}
