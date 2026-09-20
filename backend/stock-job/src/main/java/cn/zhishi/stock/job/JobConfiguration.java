package cn.zhishi.stock.job;

import cn.zhishi.stock.integration.market.SimulatedQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedTradingCalendarProvider;
import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.infrastructure.JdbcMarketOverviewArchive;
import cn.zhishi.stock.market.infrastructure.MarketOverviewJsonCodec;
import cn.zhishi.stock.market.infrastructure.RedisMarketOverviewStore;
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
}
