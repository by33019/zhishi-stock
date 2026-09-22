package cn.zhishi.stock.backend.config;

import cn.zhishi.stock.ai.application.AiContextPreviewService;
import cn.zhishi.stock.ai.application.AiFeedbackService;
import cn.zhishi.stock.ai.application.AiHistoryService;
import cn.zhishi.stock.ai.application.AiReportQueryService;
import cn.zhishi.stock.ai.application.AiTaskRequestResolver;
import cn.zhishi.stock.ai.application.AiTargetHydrator;
import cn.zhishi.stock.ai.application.AiTaskService;
import cn.zhishi.stock.ai.application.AiTaskStreamRelay;
import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiSceneCatalog;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.infrastructure.AiContextSnapshotMapper;
import cn.zhishi.stock.ai.infrastructure.AiFeedbackMapper;
import cn.zhishi.stock.ai.infrastructure.AiMessageMapper;
import cn.zhishi.stock.ai.infrastructure.AiReportMapper;
import cn.zhishi.stock.ai.infrastructure.AiSessionMapper;
import cn.zhishi.stock.ai.infrastructure.AiTaskMapper;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiContextSnapshotStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiFeedbackStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiMessageStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiReportStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiSessionStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiTaskStore;
import cn.zhishi.stock.ai.infrastructure.RedisAiTaskEventStream;
import cn.zhishi.stock.ai.infrastructure.RedisAiTaskQueue;
import cn.zhishi.stock.backend.security.JwtAuthenticationFilter;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.integration.ai.SimulatedContentHasher;
import cn.zhishi.stock.integration.ai.LlmProviderFactory;
import cn.zhishi.stock.integration.market.SimulatedKlineProvider;
import cn.zhishi.stock.integration.market.SimulatedLimitRuleProvider;
import cn.zhishi.stock.integration.market.SimulatedQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedQuoteSnapshotProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityIdentityProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityMasterProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedSectorIdentityProvider;
import cn.zhishi.stock.integration.market.SimulatedSectorProvider;
import cn.zhishi.stock.integration.market.SimulatedTradingCalendarProvider;
import cn.zhishi.stock.integration.market.SimulatedTurnoverTrendProvider;
import cn.zhishi.stock.integration.news.SimulatedNewsProvider;
import cn.zhishi.stock.integration.news.SimulatedRelationCatalogProvider;
import cn.zhishi.stock.market.application.MarketBreadthQueryService;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.application.MarketStatusQueryService;
import cn.zhishi.stock.market.application.SecurityDetailQueryService;
import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.application.SectorQueryService;
import cn.zhishi.stock.market.application.SectorRankingQueryService;
import cn.zhishi.stock.market.application.StockRankingQueryService;
import cn.zhishi.stock.market.application.TurnoverTrendQueryService;
import cn.zhishi.stock.market.domain.KlineProvider;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TurnoverTrendProvider;
import cn.zhishi.stock.market.infrastructure.JdbcMarketOverviewArchive;
import cn.zhishi.stock.market.infrastructure.MarketOverviewJsonCodec;
import cn.zhishi.stock.market.infrastructure.RedisMarketOverviewStore;
import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.news.application.NewsQueryService;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsCountProvider;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsProvider;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.RelationCatalogProvider;
import cn.zhishi.stock.news.infrastructure.MyBatisNewsArticleStore;
import cn.zhishi.stock.news.infrastructure.MyBatisNewsRelationStore;
import cn.zhishi.stock.news.infrastructure.MyBatisNewsSourceStore;
import cn.zhishi.stock.news.infrastructure.NewsArticleMapper;
import cn.zhishi.stock.news.infrastructure.NewsRelationMapper;
import cn.zhishi.stock.news.infrastructure.NewsSourceMapper;
import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.JwtAccessTokenService;
import cn.zhishi.stock.system.auth.LoginAttemptStore;
import cn.zhishi.stock.system.auth.MyBatisUserAccountRepository;
import cn.zhishi.stock.system.auth.RedisAccessTokenBlacklist;
import cn.zhishi.stock.system.auth.RedisLoginAttemptStore;
import cn.zhishi.stock.system.auth.RedisRefreshSessionStore;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.RefreshSessionStore;
import cn.zhishi.stock.system.auth.SecureOpaqueTokenGenerator;
import cn.zhishi.stock.system.auth.SessionTokenIssuer;
import cn.zhishi.stock.system.auth.SysUserMapper;
import cn.zhishi.stock.system.auth.TokenIssuer;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import cn.zhishi.stock.system.idempotency.RedisIdempotencyStore;
import cn.zhishi.stock.system.watchlist.MyBatisWatchlistGroupRepository;
import cn.zhishi.stock.system.watchlist.MyBatisWatchlistItemRepository;
import cn.zhishi.stock.system.watchlist.WatchlistGroupMapper;
import cn.zhishi.stock.system.watchlist.WatchlistGroupRepository;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import cn.zhishi.stock.system.watchlist.WatchlistItemMapper;
import cn.zhishi.stock.system.watchlist.WatchlistItemRepository;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class BackendConfiguration {

    @Bean
    TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    Clock applicationClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserAccountRepository userAccountRepository(SysUserMapper mapper) {
        return new MyBatisUserAccountRepository(mapper);
    }

    @Bean
    WatchlistGroupRepository watchlistGroupRepository(WatchlistGroupMapper mapper) {
        return new MyBatisWatchlistGroupRepository(mapper);
    }

    @Bean
    WatchlistGroupService watchlistGroupService(
            WatchlistGroupRepository watchlistGroupRepository,
            LongSupplier databaseIdGenerator,
            Clock clock) {
        return new WatchlistGroupService(watchlistGroupRepository, databaseIdGenerator, clock);
    }

    @Bean
    WatchlistItemRepository watchlistItemRepository(WatchlistItemMapper mapper) {
        return new MyBatisWatchlistItemRepository(mapper);
    }

    /** 证券身份（字符串 {@code securityId} ↔ 自选表 bigint 代理键）的唯一解析入口。 */
    @Bean
    SecurityIdentityProvider securityIdentityProvider(
            SecurityMasterProvider securityMasterProvider) {
        return new SimulatedSecurityIdentityProvider(securityMasterProvider);
    }

    @Bean
    WatchlistItemService watchlistItemService(
            WatchlistItemRepository watchlistItemRepository,
            WatchlistGroupRepository watchlistGroupRepository,
            SecurityIdentityProvider securityIdentityProvider,
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider,
            NewsCountProvider newsCountProvider,
            LongSupplier databaseIdGenerator,
            Clock clock) {
        return new WatchlistItemService(
                watchlistItemRepository,
                watchlistGroupRepository,
                securityIdentityProvider,
                quoteSnapshotBatchProvider,
                newsCountProvider,
                databaseIdGenerator,
                clock);
    }

    /** 契约 §3.7：幂等键有效窗口默认 24 小时。 */
    @Bean
    IdempotencyStore idempotencyStore(StringRedisTemplate redis) {
        return new RedisIdempotencyStore(redis, IdempotencyGuard.WINDOW);
    }

    @Bean
    IdempotencyGuard idempotencyGuard(IdempotencyStore idempotencyStore, ObjectMapper objectMapper) {
        return new IdempotencyGuard(idempotencyStore, objectMapper);
    }

    @Bean
    LoginAttemptStore loginAttemptStore(StringRedisTemplate redis) {
        return new RedisLoginAttemptStore(redis, Duration.ofMinutes(30));
    }

    @Bean
    JwtAccessTokenService jwtAccessTokenService(
            @Value("${stock.auth.jwt-secret}") String secret,
            Clock clock) {
        return new JwtAccessTokenService(secret, clock, Duration.ofMinutes(15));
    }

    @Bean
    AccessTokenBlacklist accessTokenBlacklist(StringRedisTemplate redis, Clock clock) {
        return new RedisAccessTokenBlacklist(redis, clock);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtAccessTokenService tokens,
            AccessTokenBlacklist blacklist,
            UserAccountRepository accounts) {
        return new JwtAuthenticationFilter(tokens, blacklist, accounts);
    }

    @Bean
    RefreshSessionStore refreshSessionStore(StringRedisTemplate redis, Clock clock) {
        return new RedisRefreshSessionStore(redis, clock, Duration.ofMinutes(15));
    }

    @Bean
    RefreshSessionService refreshSessionService(
            RefreshSessionStore sessions,
            UserAccountRepository accounts,
            JwtAccessTokenService accessTokens,
            Clock clock) {
        return new RefreshSessionService(
                sessions,
                accounts,
                accessTokens,
                new SecureOpaqueTokenGenerator(),
                clock,
                Duration.ofDays(7));
    }

    @Bean
    TokenIssuer tokenIssuer(RefreshSessionService sessions) {
        return new SessionTokenIssuer(sessions);
    }

    @Bean
    AuthenticationService authenticationService(
            UserAccountRepository accounts,
            LoginAttemptStore attempts,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            Clock clock) {
        return new AuthenticationService(
                accounts,
                attempts,
                passwordEncoder,
                tokenIssuer,
                clock,
                5,
                Duration.ofMinutes(15));
    }

    @Bean
    MarketOverviewJsonCodec marketOverviewJsonCodec(ObjectMapper objectMapper) {
        return new MarketOverviewJsonCodec(objectMapper);
    }

    @Bean
    MarketOverviewStore marketOverviewStore(
            StringRedisTemplate redis,
            MarketOverviewJsonCodec codec) {
        return new RedisMarketOverviewStore(redis, codec, Duration.ofMinutes(10));
    }

    @Bean
    LongSupplier databaseIdGenerator() {
        AtomicLong sequence = new AtomicLong(System.currentTimeMillis() << 12);
        return sequence::incrementAndGet;
    }

    @Bean
    MarketOverviewArchive marketOverviewArchive(
            JdbcTemplate jdbc,
            MarketOverviewJsonCodec codec,
            LongSupplier databaseIdGenerator) {
        return new JdbcMarketOverviewArchive(jdbc, codec, databaseIdGenerator);
    }

    @Bean
    MarketOverviewQueryService marketOverviewQueryService(
            MarketOverviewStore store,
            MarketOverviewArchive archive) {
        return new MarketOverviewQueryService(store, archive);
    }

    @Bean
    MarketBreadthQueryService marketBreadthQueryService(
            MarketOverviewQueryService marketOverviewQueryService) {
        return new MarketBreadthQueryService(marketOverviewQueryService);
    }

    @Bean
    TurnoverTrendProvider turnoverTrendProvider(
            Clock clock,
            TradingCalendarProvider tradingCalendarProvider) {
        return new SimulatedTurnoverTrendProvider(clock, tradingCalendarProvider);
    }

    @Bean
    TurnoverTrendQueryService turnoverTrendQueryService(
            TurnoverTrendProvider turnoverTrendProvider) {
        return new TurnoverTrendQueryService(turnoverTrendProvider);
    }

    @Bean
    LimitRuleProvider limitRuleProvider() {
        return new SimulatedLimitRuleProvider();
    }

    /**
     * 证券全集生成器。{@link SimulatedQuoteProvider} 内部仍自建一份实例——
     * 该实现无状态且完全确定性，两份实例产出逐位相同，故不做改造以保持改动最小。
     */
    @Bean
    SecurityQuoteProvider securityQuoteProvider(LimitRuleProvider limitRuleProvider) {
        return new SimulatedSecurityQuoteProvider(limitRuleProvider);
    }

    /**
     * 单只查询与整批查询由**同一个实例**承担，因此两条路径共用同一套装配。
     *
     * <p>这里只声明一个具体类型的 Bean，不再额外声明两个别名 Bean：别名 Bean 会让
     * Spring 在按具体类型解析时看到两个候选（别名 Bean 的运行时类型同样是本类型），
     * 反而需要 {@code @Qualifier} 才能消歧。依赖方按自己需要的端口声明参数即可，
     * Spring 会按可赋值性解析到这一个实例。
     *
     * <p>若拆成两个实例，两者就会各自持有一份装配逻辑，将来改一处就会让
     * 榜单与个股页对同一只证券给出不同价格，且不会有任何测试变红。
     */
    @Bean
    SimulatedQuoteSnapshotProvider quoteSnapshotProvider(
            SecurityQuoteProvider securityQuoteProvider,
            SecurityMasterProvider securityMasterProvider,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        return new SimulatedQuoteSnapshotProvider(
                securityQuoteProvider,
                securityMasterProvider,
                limitRuleProvider,
                tradingCalendarProvider,
                clock);
    }

    @Bean
    QuoteProvider quoteProvider(
            Clock clock,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider,
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider,
            SectorProvider sectorProvider,
            @Value("${stock.market.scenario:NORMAL}") String scenario) {
        return new SimulatedQuoteProvider(
                clock,
                SimulatedQuoteProvider.Scenario.valueOf(scenario.toUpperCase()),
                limitRuleProvider,
                tradingCalendarProvider,
                quoteSnapshotBatchProvider,
                sectorProvider);
    }

    @Bean
    TradingCalendarProvider tradingCalendarProvider(
            Clock clock,
            @Value("${stock.market.holidays:}") String holidays) {
        return SimulatedTradingCalendarProvider.ofCsv(clock, holidays);
    }

    @Bean
    SecurityMasterProvider securityMasterProvider(
            SecurityQuoteProvider securityQuoteProvider,
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        return new SimulatedSecurityMasterProvider(
                securityQuoteProvider, tradingCalendarProvider, clock);
    }

    @Bean
    SectorProvider sectorProvider(SecurityMasterProvider securityMasterProvider) {
        return new SimulatedSectorProvider(securityMasterProvider);
    }

    /**
     * 板块身份（字符串 {@code sectorId} ↔ 板块表 bigint 代理键）的唯一解析入口。
     *
     * <p>与 {@link #securityIdentityProvider} 同因同形：资讯关联表把 {@code target_id} 统一定义为
     * bigint（V4），因此板块侧也需要一个桥接。构词规则只在 {@code SimulatedSectorIds} 里定义一份。
     */
    @Bean
    SectorIdentityProvider sectorIdentityProvider(SectorProvider sectorProvider) {
        return new SimulatedSectorIdentityProvider(sectorProvider);
    }

    @Bean
    NewsProvider newsProvider(
            Clock clock,
            SecurityMasterProvider securityMasterProvider,
            SectorProvider sectorProvider,
            TradingCalendarProvider tradingCalendarProvider) {
        return new SimulatedNewsProvider(
                clock, securityMasterProvider, sectorProvider, tradingCalendarProvider);
    }

    @Bean
    NewsSourceStore newsSourceStore(NewsSourceMapper mapper, Clock clock) {
        return new MyBatisNewsSourceStore(mapper, clock);
    }

    /**
     * 稿件仓储依赖来源仓储：契约要求列表里不出现"来源缺失"的稿件
     * （等价于 {@code INNER JOIN news_source}），来源当前状态是可见性判据的一部分。
     */
    @Bean
    NewsArticleStore newsArticleStore(
            NewsArticleMapper articles, NewsSourceStore sources, NewsRelationMapper relations, Clock clock) {
        return new MyBatisNewsArticleStore(articles, sources, relations, clock);
    }

    @Bean
    NewsRelationStore newsRelationStore(NewsRelationMapper mapper) {
        return new MyBatisNewsRelationStore(mapper);
    }

    /** 关联解析所需的证券/板块目录，投影自行情域主数据——不在这里另造一份名字表。 */
    @Bean
    RelationCatalogProvider relationCatalogProvider(
            SecurityMasterProvider securityMasterProvider,
            SecurityIdentityProvider securityIdentityProvider,
            SectorProvider sectorProvider,
            SectorIdentityProvider sectorIdentityProvider) {
        return new SimulatedRelationCatalogProvider(
                securityMasterProvider,
                securityIdentityProvider,
                sectorProvider,
                sectorIdentityProvider);
    }

    @Bean
    NewsIngestionService newsIngestionService(
            NewsProvider newsProvider,
            NewsSourceStore newsSourceStore,
            NewsArticleStore newsArticleStore,
            NewsRelationStore newsRelationStore,
            RelationCatalogProvider relationCatalogProvider,
            LongSupplier databaseIdGenerator,
            Clock clock) {
        return new NewsIngestionService(
                newsProvider,
                newsSourceStore,
                newsArticleStore,
                newsRelationStore,
                relationCatalogProvider,
                databaseIdGenerator,
                clock);
    }

    /**
     * 资讯查询用例同时充当 {@code NewsCountProvider}（自选页的 {@code latestNewsCount}）。
     *
     * <p>刻意**不**再声明一个只做计数的 Bean：那样"哪些资讯算可见"就会有两份实现，
     * 而两份口径分歧不会报错，只会让自选卡片上的数字与资讯列表对不上。
     */
    @Bean
    NewsQueryService newsQueryService(
            NewsArticleStore newsArticleStore,
            NewsSourceStore newsSourceStore,
            SecurityIdentityProvider securityIdentityProvider,
            SectorIdentityProvider sectorIdentityProvider,
            Clock clock) {
        return new NewsQueryService(
                newsArticleStore,
                newsSourceStore,
                securityIdentityProvider,
                sectorIdentityProvider,
                clock);
    }

    @Bean
    SecurityQueryService securityQueryService(
            SecurityMasterProvider securityMasterProvider, SectorProvider sectorProvider) {
        return new SecurityQueryService(securityMasterProvider, sectorProvider);
    }

    @Bean
    KlineProvider klineProvider(
            SecurityQuoteProvider securityQuoteProvider,
            SecurityMasterProvider securityMasterProvider,
            LimitRuleProvider limitRuleProvider,
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        return new SimulatedKlineProvider(
                securityQuoteProvider,
                securityMasterProvider,
                limitRuleProvider,
                tradingCalendarProvider,
                clock);
    }

    @Bean
    SecurityDetailQueryService securityDetailQueryService(
            QuoteSnapshotProvider quoteSnapshotProvider,
            KlineProvider klineProvider,
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        return new SecurityDetailQueryService(
                quoteSnapshotProvider, klineProvider, tradingCalendarProvider, clock);
    }

    @Bean
    StockRankingQueryService stockRankingQueryService(
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider, SectorProvider sectorProvider) {
        return new StockRankingQueryService(quoteSnapshotBatchProvider, sectorProvider);
    }

    @Bean
    SectorQueryService sectorQueryService(
            SectorProvider sectorProvider, QuoteSnapshotBatchProvider quoteSnapshotBatchProvider) {
        return new SectorQueryService(sectorProvider, quoteSnapshotBatchProvider);
    }

    @Bean
    SectorRankingQueryService sectorRankingQueryService(
            SectorProvider sectorProvider, QuoteSnapshotBatchProvider quoteSnapshotBatchProvider) {
        return new SectorRankingQueryService(sectorProvider, quoteSnapshotBatchProvider);
    }

    @Bean
    MarketStatusQueryService marketStatusQueryService(
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        return new MarketStatusQueryService(tradingCalendarProvider, clock);
    }

    // ---------- AI 域（M3-06） ----------

    /** AI 场景目录：纯静态规则表，没有依赖，也不依赖时钟。 */
    @Bean
    AiSceneCatalog aiSceneCatalog() {
        return new AiSceneCatalog();
    }

    /** 上下文内容哈希：模拟实现是确定性的，同一内容两次构建得到同一个 hash。 */
    @Bean
    AiContentHasher aiContentHasher() {
        return new SimulatedContentHasher();
    }

    /**
     * AI 任务上下文构建器。
     *
     * <p>资讯证据端口**复用** {@code newsQueryService}——它已实现
     * {@code NewsEvidenceProvider}。再声明一个只做取证的 Bean，会让"哪些资讯可进 AI"
     * 出现两份实现，而口径分歧不会报错，只会让预览说 20 条、报告里只有 18 条
     * （同 {@code NewsCountProvider} 的处理方式）。
     */
    @Bean
    AiContextBuilder aiContextBuilder(
            QuoteSnapshotBatchProvider quoteSnapshotBatchProvider,
            MarketOverviewQueryService marketOverviewQueryService,
            SectorProvider sectorProvider,
            NewsEvidenceProvider newsEvidenceProvider,
            AiContentHasher aiContentHasher,
            @Value("${stock.ai.news-evidence-limit:20}") int newsEvidenceLimit) {
        return new AiContextBuilder(
                quoteSnapshotBatchProvider,
                marketOverviewQueryService,
                sectorProvider,
                newsEvidenceProvider,
                aiContentHasher,
                newsEvidenceLimit);
    }

    @Bean
    AiContextPreviewService aiContextPreviewService(
            AiTaskRequestResolver aiTaskRequestResolver, AiContextBuilder aiContextBuilder) {
        return new AiContextPreviewService(aiTaskRequestResolver, aiContextBuilder);
    }

    /**
     * LLM Provider 端口。当前只有确定性模拟实现；换真实供应商时改这里一处即可，
     * 用例层不认识具体实现（架构 §11.3）。
     */
    @Bean
    LlmProviderPort llmProviderPort(
            AiContentHasher aiContentHasher,
            ObjectMapper objectMapper,
            @Value("${stock.ai.provider-code:SIMULATED}") String providerCode,
            @Value("${stock.ai.model-code:sim-analyst-v1}") String modelCode,
            @Value("${stock.ai.llm-mode:SIMULATED}") String llmMode,
            @Value("${stock.ai.base-url:}") String baseUrl,
            @Value("${stock.ai.api-key:}") String apiKey,
            @Value("${stock.ai.llm-timeout-seconds:120}") long llmTimeoutSeconds) {
        // 装配逻辑收在工厂里，与 stock-ai-worker 共用同一处——两个进程选了不同实现时，
        // 在线侧按真实模型报"将使用的数据"、执行侧却产出占位正文，而两边各自看都正常。
        return LlmProviderFactory.create(
                LlmProviderFactory.modeOf(llmMode),
                aiContentHasher,
                objectMapper,
                providerCode,
                modelCode,
                baseUrl,
                apiKey,
                Duration.ofSeconds(llmTimeoutSeconds));
    }

    // ---------- AI 域：任务编排（M3-07） ----------

    /**
     * 任务请求解析器。
     *
     * <p>预览（AI-02）与创建（AI-03）**共用同一个实例**：两份实现会让同一份非法请求
     * 在两个入口报出不同的业务码或不同的文案，而用户看到的解释就会随入口变化。
     */
    @Bean
    AiTaskRequestResolver aiTaskRequestResolver(
            AiSceneCatalog aiSceneCatalog,
            SecurityIdentityProvider securityIdentityProvider,
            SectorIdentityProvider sectorIdentityProvider) {
        return new AiTaskRequestResolver(
                aiSceneCatalog, securityIdentityProvider, sectorIdentityProvider);
    }

    /**
     * 任务目标的对外标识还原器。
     *
     * <p>它与 {@code aiTaskRequestResolver} 是**两个不同的东西**：解析器把"请求里的标识"
     * 变成代理键，这个还原器把"库里的代理键"变回标识。两个方向都需要，
     * 而且都必须只有一份实现——执行器、重试、追问、任务摘要四处都走它。
     */
    @Bean
    AiTargetHydrator aiTargetHydrator(
            SecurityIdentityProvider securityIdentityProvider,
            SectorIdentityProvider sectorIdentityProvider) {
        return new AiTargetHydrator(securityIdentityProvider, sectorIdentityProvider);
    }

    /**
     * 任务聚合存储。
     *
     * <p>ID 用 {@code databaseIdGenerator}（数据库自增段），与自选、资讯一致；
     * 不再有第二个 ID 生成器。
     */
    @Bean
    AiTaskStore aiTaskStore(AiTaskMapper mapper, LongSupplier databaseIdGenerator, Clock clock) {
        return new MyBatisAiTaskStore(mapper, databaseIdGenerator, clock);
    }

    @Bean
    AiSessionStore aiSessionStore(AiSessionMapper mapper, Clock clock) {
        return new MyBatisAiSessionStore(mapper, clock);
    }

    @Bean
    AiMessageStore aiMessageStore(AiMessageMapper mapper, Clock clock) {
        return new MyBatisAiMessageStore(mapper, clock);
    }

    @Bean
    AiReportStore aiReportStore(AiReportMapper mapper, Clock clock) {
        return new MyBatisAiReportStore(mapper, clock);
    }

    /**
     * 上下文快照存储。
     *
     * <p>依赖 {@code ObjectMapper} 把 {@code context_data} 序列化成 JSON 列——
     * 该列的内容形状（资讯证据 / 行情字段）由 M3-06 的 {@code AiContextBuilder} 决定，
     * 这里只负责原样存取，不做二次加工。
     */
    @Bean
    AiContextSnapshotStore aiContextSnapshotStore(
            AiContextSnapshotMapper mapper,
            ObjectMapper objectMapper,
            LongSupplier databaseIdGenerator,
            Clock clock) {
        return new MyBatisAiContextSnapshotStore(mapper, objectMapper, databaseIdGenerator, clock);
    }

    /**
     * 投递队列。Web 侧**只入队、不消费**，所以这里的消费者名只用于日志可读性；
     * 真正的消费方是 {@code stock-ai-worker}，它有自己的一份（消费者名 = 实例名）。
     */
    @Bean
    AiTaskQueue aiTaskQueue(
            StringRedisTemplate stringRedisTemplate,
            @Value("${spring.application.name:stock-backend}") String applicationName) {
        return new RedisAiTaskQueue(stringRedisTemplate, applicationName);
    }

    /**
     * 任务事件流（SSE 的数据源）。
     *
     * <p>保留期取配置：契约 §13.4 允许"连接结束后片段可被清理"，而报告本身
     * 落在 {@code ai_report} 里，所以清理片段不会丢结论。
     */
    @Bean
    AiTaskEventStream aiTaskEventStream(
            StringRedisTemplate stringRedisTemplate,
            @Value("${stock.ai.chunk-retention-minutes:30}") long retentionMinutes) {
        return new RedisAiTaskEventStream(stringRedisTemplate, Duration.ofMinutes(retentionMinutes));
    }

    /**
     * SSE 中继。
     *
     * <p>它不依赖任何 Web 类型（{@code stock-ai} 里没有 spring-web），
     * 所以"往哪写"由 {@code AiTaskEventSink} 注入；本模块提供
     * {@code SseTaskEventSink} 实现。这样中继的循环逻辑能用假实现单测，
     * 而不用起一个真的 Servlet 容器。
     */
    @Bean
    AiTaskStreamRelay aiTaskStreamRelay(
            AiTaskEventStream aiTaskEventStream,
            AiTaskStore aiTaskStore,
            @Value("${stock.ai.stream.batch-size:100}") int batchSize,
            @Value("${stock.ai.stream.poll-delay-ms:200}") long pollDelayMillis,
            @Value("${stock.ai.stream.max-duration-seconds:900}") long maxDurationSeconds,
            @Value("${stock.ai.stream.partial-content-scan-limit:500}") int partialContentScanLimit) {
        return new AiTaskStreamRelay(
                aiTaskEventStream,
                aiTaskStore,
                batchSize,
                Duration.ofMillis(pollDelayMillis),
                Duration.ofSeconds(maxDurationSeconds),
                partialContentScanLimit);
    }

    /**
     * SSE 中继的线程池。
     *
     * <p>Servlet 栈下**每条 SSE 连接占一个线程**（中继是阻塞循环），
     * 所以这个池的大小就是并发 SSE 连接数的上限。设小了会让新连接排队到超时，
     * 设大了只是多占内存。用守护线程：应用关闭时不必等它们。
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService aiStreamExecutor(
            @Value("${stock.ai.stream.threads:32}") int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "ai-stream-relay");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 报告反馈仓储（{@code ai_feedback}）。
     *
     * <p>只有在线侧需要它：写入发生在用户提交反馈时，而 worker 只跑任务、不碰反馈。
     * 因此这里声明，{@code AiWorkerConfiguration} 不声明——少一个 Bean 就少一处
     * "哪个进程需要它"的猜测。
     */
    @Bean
    AiFeedbackStore aiFeedbackStore(AiFeedbackMapper aiFeedbackMapper, Clock clock) {
        return new MyBatisAiFeedbackStore(aiFeedbackMapper, clock);
    }

    /**
     * 报告查询用例（HIS-06）。
     *
     * <p>注入反馈仓储是为了让报告详情一次带回"当前用户反馈"——否则前端拿到报告后
     * 还要再发一次请求才知道自己评过没有，而那次请求的失败会让"未评价"与"查询失败"
     * 看起来一样。
     */
    @Bean
    AiReportQueryService aiReportQueryService(
            AiReportStore aiReportStore, AiTaskStore aiTaskStore, AiFeedbackStore aiFeedbackStore) {
        return new AiReportQueryService(aiReportStore, aiTaskStore, aiFeedbackStore);
    }

    /**
     * 会话历史用例（HIS-01 列表 / HIS-02 详情 / HIS-05 消息）。
     *
     * <p>详情要拼最近任务与报告摘要，因此比另两个读接口多依赖任务与报告仓储，
     * 以及把目标代理键还原成对外标识的 {@code AiTargetHydrator}——
     * 少了它，前端拿到的目标主键解析不了。
     */
    @Bean
    AiHistoryService aiHistoryService(
            AiSessionStore aiSessionStore,
            AiMessageStore aiMessageStore,
            AiTaskStore aiTaskStore,
            AiReportStore aiReportStore,
            AiTargetHydrator aiTargetHydrator) {
        return new AiHistoryService(
                aiSessionStore, aiMessageStore, aiTaskStore, aiReportStore, aiTargetHydrator);
    }

    /**
     * 反馈用例（HIS-08 / HIS-09）。
     *
     * <p>依赖 {@code AiReportQueryService} 而不是自己再写一份归属判据：能写反馈的前提
     * 与能读报告的前提必须是同一条，各写一份会分叉成"读不到的报告却能打分"。
     */
    @Bean
    AiFeedbackService aiFeedbackService(
            AiReportQueryService aiReportQueryService,
            AiFeedbackStore aiFeedbackStore,
            LongSupplier databaseIdGenerator,
            Clock clock) {
        return new AiFeedbackService(
                aiReportQueryService, aiFeedbackStore, databaseIdGenerator, clock);
    }

    /**
     * 任务编排用例。
     *
     * <p>刻意**不注入** {@code IdempotencyGuard}：幂等的第一层（回放完整响应）
     * 是 Web 层关心的事（需要记住 HTTP 响应体），用例层只认
     * {@code ai_task.request_id} 这第二层。把两层揉进一个类，会让"Redis 抖动"
     * 变成一个必须在这里处理的异常分支。
     */
    @Bean
    AiTaskService aiTaskService(
            AiTaskRequestResolver aiTaskRequestResolver,
            AiContextBuilder aiContextBuilder,
            AiTaskStore aiTaskStore,
            AiSessionStore aiSessionStore,
            AiMessageStore aiMessageStore,
            AiReportStore aiReportStore,
            AiTaskQueue aiTaskQueue,
            AiTargetHydrator aiTargetHydrator,
            LongSupplier databaseIdGenerator,
            Clock clock,
            @Value("${stock.ai.daily-task-limit:20}") int dailyTaskLimit,
            @Value("${stock.ai.max-concurrent-tasks:2}") int maxConcurrentTasks,
            @Value("${stock.ai.task-deadline-seconds:180}") long taskDeadlineSeconds,
            @Value("${stock.ai.provider-code:SIMULATED}") String providerCode,
            @Value("${stock.ai.model-code:sim-analyst-v1}") String modelCode) {
        return new AiTaskService(
                aiTaskRequestResolver,
                aiContextBuilder,
                aiTaskStore,
                aiSessionStore,
                aiMessageStore,
                aiReportStore,
                aiTaskQueue,
                aiTargetHydrator,
                databaseIdGenerator,
                clock,
                dailyTaskLimit,
                maxConcurrentTasks,
                Duration.ofSeconds(taskDeadlineSeconds),
                providerCode,
                modelCode);
    }
}
