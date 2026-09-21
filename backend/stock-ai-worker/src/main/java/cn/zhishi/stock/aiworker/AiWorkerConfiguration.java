package cn.zhishi.stock.aiworker;

import cn.zhishi.stock.ai.application.AiTaskExecutionService;
import cn.zhishi.stock.ai.application.AiTaskRecoveryService;
import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.infrastructure.AiContextSnapshotMapper;
import cn.zhishi.stock.ai.infrastructure.AiMessageMapper;
import cn.zhishi.stock.ai.infrastructure.AiReportMapper;
import cn.zhishi.stock.ai.infrastructure.AiTaskMapper;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiContextSnapshotStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiMessageStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiReportStore;
import cn.zhishi.stock.ai.infrastructure.MyBatisAiTaskStore;
import cn.zhishi.stock.ai.infrastructure.RedisAiTaskEventStream;
import cn.zhishi.stock.ai.infrastructure.RedisAiTaskQueue;
import cn.zhishi.stock.integration.ai.SimulatedContentHasher;
import cn.zhishi.stock.integration.ai.SimulatedLlmProvider;
import cn.zhishi.stock.integration.market.SimulatedLimitRuleProvider;
import cn.zhishi.stock.integration.market.SimulatedQuoteSnapshotProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityIdentityProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityMasterProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedSectorIdentityProvider;
import cn.zhishi.stock.integration.market.SimulatedSectorProvider;
import cn.zhishi.stock.integration.market.SimulatedTradingCalendarProvider;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.infrastructure.JdbcMarketOverviewArchive;
import cn.zhishi.stock.market.infrastructure.MarketOverviewJsonCodec;
import cn.zhishi.stock.market.infrastructure.RedisMarketOverviewStore;
import cn.zhishi.stock.news.application.NewsQueryService;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.infrastructure.MyBatisNewsArticleStore;
import cn.zhishi.stock.news.infrastructure.MyBatisNewsSourceStore;
import cn.zhishi.stock.news.infrastructure.NewsArticleMapper;
import cn.zhishi.stock.news.infrastructure.NewsRelationMapper;
import cn.zhishi.stock.news.infrastructure.NewsSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 执行器的装配。
 *
 * <h2>为什么这里必须把取数链路重新装一遍，而不是复用 stock-backend 的配置类</h2>
 * 架构约定是「Store 实现留域模块，Bean 在**应用模块**声明」，而应用模块有三个：
 * {@code stock-backend}（查询侧）、{@code stock-job}（采集侧）、本模块（写入侧）。
 * 三者各自持有一份"这个域由哪些实现组成"的答案。
 *
 * <p>代价是"改一处要同步三处"，收益是 worker 不依赖 Web 模块——
 * 否则为了拿到 {@code AiContextBuilder} 会把 Spring Security、Tomcat 与整套鉴权链路
 * 一起拖进一个**不开放 HTTP** 的进程里。
 *
 * <p>降低代价的办法是把**口径**收在域里（{@code TradingSessions} /
 * {@code QuoteBatch} / {@code SectorQuoteCalculator} / {@code SimulatedSecurityIds}），
 * 让这里只剩下"把谁交给谁"。真正会分叉的是口径，不是装配顺序——
 * 所以本文件刻意不自己算任何东西，只做转发与取值。
 *
 * <h2>刻意不装配的东西</h2>
 * 不声明 {@code AiTaskService}（那是 Web 侧创建任务的用例）、不声明
 * {@code AiContextPreviewService}（预览接口用）、不声明 {@code NewsIngestionService}
 * （采集属于 {@code stock-job}）。把它们拖进来只会让 worker 依赖自己永远不走的代码路径。
 */
@Configuration
public class AiWorkerConfiguration {

    @Bean
    Clock workerClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 数据库自增段 ID 生成器。
     *
     * <p>与 {@code stock-backend} / {@code stock-job} 的取值方式一致（同一种构词），
     * 但**不是同一个实例**——不同进程各持一份是必然的，只要它们不冲突即可
     * （高位取当前毫秒、低位自增，跨进程撞车概率可忽略）。
     */
    @Bean
    LongSupplier workerDatabaseIdGenerator() {
        AtomicLong sequence = new AtomicLong(System.currentTimeMillis() << 12);
        return sequence::incrementAndGet;
    }

    // ------------------------------------------------ 取数链路（行情 / 资讯）

    @Bean
    MarketOverviewJsonCodec marketOverviewJsonCodec(ObjectMapper objectMapper) {
        return new MarketOverviewJsonCodec(objectMapper);
    }

    @Bean
    MarketOverviewStore marketOverviewStore(
            StringRedisTemplate redis, MarketOverviewJsonCodec codec) {
        return new RedisMarketOverviewStore(redis, codec, Duration.ofMinutes(10));
    }

    @Bean
    MarketOverviewArchive marketOverviewArchive(
            JdbcTemplate jdbc,
            MarketOverviewJsonCodec codec,
            LongSupplier workerDatabaseIdGenerator) {
        return new JdbcMarketOverviewArchive(jdbc, codec, workerDatabaseIdGenerator);
    }

    @Bean
    MarketOverviewQueryService marketOverviewQueryService(
            MarketOverviewStore store, MarketOverviewArchive archive) {
        return new MarketOverviewQueryService(store, archive);
    }

    @Bean
    LimitRuleProvider limitRuleProvider() {
        return new SimulatedLimitRuleProvider();
    }

    @Bean
    SecurityQuoteProvider securityQuoteProvider(LimitRuleProvider limitRuleProvider) {
        return new SimulatedSecurityQuoteProvider(limitRuleProvider);
    }

    @Bean
    TradingCalendarProvider tradingCalendarProvider(
            Clock clock, @Value("${stock.market.holidays:}") String holidays) {
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

    @Bean
    SecurityIdentityProvider securityIdentityProvider(
            SecurityMasterProvider securityMasterProvider) {
        return new SimulatedSecurityIdentityProvider(securityMasterProvider);
    }

    @Bean
    SectorIdentityProvider sectorIdentityProvider(SectorProvider sectorProvider) {
        return new SimulatedSectorIdentityProvider(sectorProvider);
    }

    /**
     * 整批快照。{@code AiContextBuilder} 与"页面上的数字"必须走**同一个** Provider——
     * 两边各建一份会让同一只证券在报告里与页面上给出不同价格，而这类分歧不会报错。
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
    NewsSourceStore newsSourceStore(NewsSourceMapper mapper, Clock clock) {
        return new MyBatisNewsSourceStore(mapper, clock);
    }

    /**
     * 稿件仓储依赖来源仓储：契约要求列表里不出现"来源缺失"的稿件
     * （等价于 {@code INNER JOIN news_source}），来源当前状态是可见性判据的一部分。
     */
    @Bean
    NewsArticleStore newsArticleStore(
            NewsArticleMapper articles,
            NewsSourceStore sources,
            NewsRelationMapper relations,
            Clock clock) {
        return new MyBatisNewsArticleStore(articles, sources, relations, clock);
    }

    /**
     * 资讯查询用例同时充当 {@code NewsEvidenceProvider}。
     *
     * <p>再声明一个只做取证的 Bean，会让"哪些资讯可进 AI"出现两份实现，
     * 而口径分歧不会报错，只会让报告里的证据条数与资讯页对不上
     * （与 {@code stock-backend} 的处理方式一致）。
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

    // ------------------------------------------------ AI 域

    @Bean
    AiContentHasher aiContentHasher() {
        return new SimulatedContentHasher();
    }

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
    LlmProviderPort llmProviderPort(
            AiContentHasher aiContentHasher,
            @Value("${stock.ai.provider-code:SIMULATED}") String providerCode,
            @Value("${stock.ai.model-code:sim-analyst-v1}") String modelCode) {
        return new SimulatedLlmProvider(aiContentHasher, providerCode, modelCode);
    }

    /**
     * 任务聚合存储。
     *
     * <p>这里**只**需要 {@code AiTaskMapper}：worker 不读会话、不读预览，
     * 而 {@code ai_session} 的 {@code touch} 由创建任务那一侧负责。
     */
    @Bean
    AiTaskStore aiTaskStore(AiTaskMapper mapper, LongSupplier workerDatabaseIdGenerator, Clock clock) {
        return new MyBatisAiTaskStore(mapper, workerDatabaseIdGenerator, clock);
    }

    @Bean
    AiMessageStore aiMessageStore(AiMessageMapper mapper, Clock clock) {
        return new MyBatisAiMessageStore(mapper, clock);
    }

    @Bean
    AiReportStore aiReportStore(AiReportMapper mapper, Clock clock) {
        return new MyBatisAiReportStore(mapper, clock);
    }

    @Bean
    AiContextSnapshotStore aiContextSnapshotStore(
            AiContextSnapshotMapper mapper,
            ObjectMapper objectMapper,
            LongSupplier workerDatabaseIdGenerator,
            Clock clock) {
        return new MyBatisAiContextSnapshotStore(mapper, objectMapper, workerDatabaseIdGenerator, clock);
    }

    /**
     * 消费队列。消费者名取实例名——它只影响 {@code XPENDING} 里那一列的可读性，
     * 因为「谁在做这个任务」的唯一事实来源是 {@code ai_task.status}。
     */
    @Bean
    AiTaskQueue aiTaskQueue(
            StringRedisTemplate stringRedisTemplate,
            @Value("${spring.application.name:stock-ai-worker}") String applicationName) {
        return new RedisAiTaskQueue(stringRedisTemplate, applicationName);
    }

    @Bean
    AiTaskEventStream aiTaskEventStream(
            StringRedisTemplate stringRedisTemplate,
            @Value("${stock.ai.chunk-retention-minutes:30}") long retentionMinutes) {
        return new RedisAiTaskEventStream(stringRedisTemplate, Duration.ofMinutes(retentionMinutes));
    }

    /**
     * 任务执行器。
     *
     * <p>{@code promptVersion} / {@code contentSchemaVersion} 会被写进 {@code ai_report}：
     * 它们是"这份报告是用哪一版提示词与内容结构生成的"的唯一答案。
     * 两个进程（Web 创建、Worker 执行）**不共用**它们——创建侧写的是任务快照，
     * 执行侧写的是报告，各自独立取值；换 Prompt 时只需保证执行侧的配置生效。
     */
    @Bean
    AiTaskExecutionService aiTaskExecutionService(
            AiTaskStore aiTaskStore,
            AiContextSnapshotStore aiContextSnapshotStore,
            AiMessageStore aiMessageStore,
            AiReportStore aiReportStore,
            AiTaskEventStream aiTaskEventStream,
            AiTaskQueue aiTaskQueue,
            AiContextBuilder aiContextBuilder,
            LlmProviderPort llmProviderPort,
            AiContentHasher aiContentHasher,
            LongSupplier workerDatabaseIdGenerator,
            Clock clock,
            @Value("${stock.ai.prompt-version:p1}") String promptVersion,
            @Value("${stock.ai.content-schema-version:v1}") String contentSchemaVersion,
            @Value("${stock.ai.max-output-tokens:2048}") int maxOutputTokens) {
        return new AiTaskExecutionService(
                aiTaskStore,
                aiContextSnapshotStore,
                aiMessageStore,
                aiReportStore,
                aiTaskEventStream,
                aiTaskQueue,
                aiContextBuilder,
                llmProviderPort,
                aiContentHasher,
                workerDatabaseIdGenerator,
                clock,
                promptVersion,
                contentSchemaVersion,
                maxOutputTokens);
    }

    /**
     * 恢复扫描。
     *
     * <p>三个阈值都从配置来：{@code queueLostAfterMinutes} 决定"投递出去多久没被执行"
     * 才认为消息丢了，{@code heartbeatLostAfterMinutes} 决定"心跳多久没刷新"才认为
     * 执行者死了。后者必须**明显大于**心跳周期（每个片段都刷），
     * 否则一个正在正常生成的长任务会被误判成死执行者并被重投。
     */
    @Bean
    AiTaskRecoveryService aiTaskRecoveryService(
            AiTaskStore aiTaskStore,
            AiTaskEventStream aiTaskEventStream,
            AiTaskQueue aiTaskQueue,
            Clock clock,
            @Value("${stock.ai.worker.queue-lost-minutes:5}") long queueLostAfterMinutes,
            @Value("${stock.ai.worker.heartbeat-lost-minutes:5}") long heartbeatLostAfterMinutes,
            @Value("${stock.ai.worker.recovery-batch-limit:50}") int batchLimit) {
        return new AiTaskRecoveryService(
                aiTaskStore,
                aiTaskEventStream,
                aiTaskQueue,
                clock,
                queueLostAfterMinutes,
                heartbeatLostAfterMinutes,
                batchLimit);
    }
}
