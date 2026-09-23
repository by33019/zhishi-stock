package cn.zhishi.stock.job;

import cn.zhishi.stock.export.application.ExportRetentionSweeper;
import cn.zhishi.stock.export.domain.ExportFileStore;
import cn.zhishi.stock.export.domain.ExportJobStore;
import cn.zhishi.stock.export.domain.ExportPolicy;
import cn.zhishi.stock.export.infrastructure.ExportJobJsonCodec;
import cn.zhishi.stock.export.infrastructure.RedisExportJobStore;
import cn.zhishi.stock.export.infrastructure.VolumeExportFileStore;
import cn.zhishi.stock.integration.market.SimulatedLimitRuleProvider;
import cn.zhishi.stock.integration.market.SimulatedQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityIdentityProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityMasterProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedSectorIdentityProvider;
import cn.zhishi.stock.integration.market.SimulatedSectorProvider;
import cn.zhishi.stock.integration.market.SimulatedTradingCalendarProvider;
import cn.zhishi.stock.integration.news.SimulatedNewsProvider;
import cn.zhishi.stock.integration.news.SimulatedRelationCatalogProvider;
import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.infrastructure.JdbcMarketOverviewArchive;
import cn.zhishi.stock.market.infrastructure.MarketOverviewJsonCodec;
import cn.zhishi.stock.market.infrastructure.RedisMarketOverviewStore;
import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.news.domain.NewsArticleStore;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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

@Configuration
public class JobConfiguration {

    @Bean
    Clock jobClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

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
    LongSupplier jobDatabaseIdGenerator() {
        AtomicLong sequence = new AtomicLong(System.currentTimeMillis() << 12);
        return sequence::incrementAndGet;
    }

    @Bean
    MarketOverviewArchive marketOverviewArchive(
            JdbcTemplate jdbc, MarketOverviewJsonCodec codec, LongSupplier jobDatabaseIdGenerator) {
        return new JdbcMarketOverviewArchive(jdbc, codec, jobDatabaseIdGenerator);
    }

    /**
     * 采集侧与查询侧必须共用同一份交易日历口径。
     *
     * <p>此前本模块没有声明日历 Bean，{@code SimulatedQuoteProvider} 自建了一份
     * **空节假日表**的日历；而 `stock-backend` 用的是 {@code stock.market.holidays}。
     * 一旦配置了节假日，采集任务会认为当天是交易日并落盘快照，
     * 而查询侧按节假日回退到上一交易日——两边对"今天是哪一天"给出不同答案，
     * 且不会有任何测试报错。
     *
     * <p>资讯采集同样吃这一份日历（{@code SimulatedNewsProvider} 用它决定发布时间
     * 落在哪个交易日的时段内），所以它必须是**这一个** Bean，不能再造第二个。
     */
    @Bean
    TradingCalendarProvider tradingCalendarProvider(
            Clock clock, @Value("${stock.market.holidays:}") String holidays) {
        return SimulatedTradingCalendarProvider.ofCsv(clock, holidays);
    }

    @Bean
    QuoteProvider quoteProvider(
            Clock clock,
            TradingCalendarProvider tradingCalendarProvider,
            @Value("${stock.market.scenario:NORMAL}") String scenario) {
        return new SimulatedQuoteProvider(
                clock,
                SimulatedQuoteProvider.Scenario.valueOf(scenario.toUpperCase()),
                tradingCalendarProvider);
    }

    @Bean
    MarketIngestionService marketIngestionService(
            QuoteProvider provider,
            MarketOverviewStore store,
            MarketOverviewArchive archive,
            Clock clock) {
        return new MarketIngestionService(provider, store, archive, clock);
    }

    // ---------------------------------------------------------------- 资讯域
    //
    // 资讯采集要落库，所以这里必须把资讯域的装配链补齐。这一段与
    // `stock-backend` 的 `BackendConfiguration` **刻意同形**：两边各自持有一份
    // "资讯域由哪些实现组成"的答案，一旦分叉，采集端写进去的关联与查询端读出来的
    // 关联就会对不上，而且不会有任何测试报错（M2-10 的教训）。
    //
    // 但这里**只装配采集所需的部分**：不声明 `NewsQueryService`——定时任务不查询，
    // 把它拖进来会让 job 依赖整个查询侧的口径，白白扩大两处必须同步的范围。

    @Bean
    LimitRuleProvider limitRuleProvider() {
        return new SimulatedLimitRuleProvider();
    }

    @Bean
    SecurityQuoteProvider securityQuoteProvider(LimitRuleProvider limitRuleProvider) {
        return new SimulatedSecurityQuoteProvider(limitRuleProvider);
    }

    /** 资讯里出现的证券名与关联解析的匹配目录，都投影自这一份主数据。 */
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
     * 证券身份（字符串 {@code securityId} ↔ 关联表 bigint 代理键）的唯一解析入口。
     *
     * <p>关联表把 {@code target_id} 统一定义为 bigint（V4），与
     * {@code user_watchlist_item.security_id} 是**同一套**代理键。构词规则只在
     * {@code SimulatedSecurityIds} 里定义一份，这里不重复。
     */
    @Bean
    SecurityIdentityProvider securityIdentityProvider(
            SecurityMasterProvider securityMasterProvider) {
        return new SimulatedSecurityIdentityProvider(securityMasterProvider);
    }

    /** 板块侧与证券侧同因同形。 */
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
            NewsArticleMapper articles,
            NewsSourceStore sources,
            NewsRelationMapper relations,
            Clock clock) {
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
            LongSupplier jobDatabaseIdGenerator,
            Clock clock) {
        return new NewsIngestionService(
                newsProvider,
                newsSourceStore,
                newsArticleStore,
                newsRelationStore,
                relationCatalogProvider,
                jobDatabaseIdGenerator,
                clock);
    }

    // ---------------------------------------------------------------- 导出域（M3-12）
    //
    // 本模块只装配**清理**需要的那两个端口，不装配取数、写出、限流与审计：
    // 清理不会读行情、不生成文件、不受限流约束，把整条装配链搬过来只会让
    // "定时任务依赖什么"变得看不清（同上面资讯域"只装采集所需部分"的取舍）。
    //
    // 但目录与保留期这两项**必须与 stock-backend 完全一致**，否则会出现
    // "API 写出去的文件夹在卷 A、清理删的是卷 B"——记录被删掉、文件永远留着，
    // 且不会有任何报错。因此两处都用同一个配置键（stock.export.directory）
    // 与同一组契约常量（ExportPolicy），不在这里另写默认值。

    /** 作业记录的 JSON 编解码。与 API 侧同形，两份必须能互读（Redis 里的记录是共享事实）。 */
    @Bean
    ExportJobJsonCodec exportJobJsonCodec(ObjectMapper objectMapper) {
        return new ExportJobJsonCodec(objectMapper);
    }

    @Bean
    ExportJobStore exportJobStore(
            StringRedisTemplate redis, ExportJobJsonCodec codec, Clock clock) {
        return new RedisExportJobStore(redis, codec, ExportPolicy.RECORD_TTL, clock);
    }

    @Bean
    ExportFileStore exportFileStore(
            @Value("${stock.export.directory:./data/exports}") String directory) {
        return new VolumeExportFileStore(Path.of(directory));
    }

    @Bean
    ExportRetentionSweeper exportRetentionSweeper(ExportJobStore jobs, ExportFileStore files) {
        return new ExportRetentionSweeper(jobs, files);
    }
}
