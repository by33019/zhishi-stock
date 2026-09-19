package cn.zhishi.stock.backend.config;

import cn.zhishi.stock.backend.security.JwtAuthenticationFilter;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.integration.market.SimulatedKlineProvider;
import cn.zhishi.stock.integration.market.SimulatedLimitRuleProvider;
import cn.zhishi.stock.integration.market.SimulatedQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedQuoteSnapshotProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityMasterProvider;
import cn.zhishi.stock.integration.market.SimulatedSecurityQuoteProvider;
import cn.zhishi.stock.integration.market.SimulatedTradingCalendarProvider;
import cn.zhishi.stock.integration.market.SimulatedTurnoverTrendProvider;
import cn.zhishi.stock.market.application.MarketBreadthQueryService;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.application.MarketStatusQueryService;
import cn.zhishi.stock.market.application.SecurityDetailQueryService;
import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.application.TurnoverTrendQueryService;
import cn.zhishi.stock.market.domain.KlineProvider;
import cn.zhishi.stock.market.domain.LimitRuleProvider;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import cn.zhishi.stock.market.domain.QuoteSnapshotProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.market.domain.TradingCalendarProvider;
import cn.zhishi.stock.market.domain.TurnoverTrendProvider;
import cn.zhishi.stock.market.infrastructure.JdbcMarketOverviewArchive;
import cn.zhishi.stock.market.infrastructure.MarketOverviewJsonCodec;
import cn.zhishi.stock.market.infrastructure.RedisMarketOverviewStore;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
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

    @Bean
    QuoteProvider quoteProvider(
            Clock clock,
            LimitRuleProvider limitRuleProvider,
            @Value("${stock.market.scenario:NORMAL}") String scenario) {
        return new SimulatedQuoteProvider(
                clock,
                SimulatedQuoteProvider.Scenario.valueOf(scenario.toUpperCase()),
                limitRuleProvider);
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
    SecurityQueryService securityQueryService(SecurityMasterProvider securityMasterProvider) {
        return new SecurityQueryService(securityMasterProvider);
    }

    @Bean
    QuoteSnapshotProvider quoteSnapshotProvider(
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
    MarketStatusQueryService marketStatusQueryService(
            TradingCalendarProvider tradingCalendarProvider,
            Clock clock) {
        return new MarketStatusQueryService(tradingCalendarProvider, clock);
    }
}
