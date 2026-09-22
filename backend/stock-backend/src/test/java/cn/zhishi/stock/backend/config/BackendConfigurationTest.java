package cn.zhishi.stock.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zhishi.stock.ai.application.AiContextPreviewService;
import cn.zhishi.stock.ai.application.AiTaskRequestResolver;
import cn.zhishi.stock.ai.application.AiTaskService;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiSessionStore;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.infrastructure.AiContextSnapshotMapper;
import cn.zhishi.stock.ai.infrastructure.AiFeedbackMapper;
import cn.zhishi.stock.ai.infrastructure.AiMessageMapper;
import cn.zhishi.stock.ai.infrastructure.AiReportMapper;
import cn.zhishi.stock.ai.infrastructure.AiSessionMapper;
import cn.zhishi.stock.ai.infrastructure.AiTaskMapper;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.application.SecurityQueryService;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.market.domain.SecurityMasterProvider;
import cn.zhishi.stock.market.domain.SecurityQuoteProvider;
import cn.zhishi.stock.backend.security.JwtAuthenticationFilter;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.news.application.NewsQueryService;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsProvider;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.RelationCatalogProvider;
import cn.zhishi.stock.news.infrastructure.NewsArticleMapper;
import cn.zhishi.stock.news.infrastructure.NewsRelationMapper;
import cn.zhishi.stock.news.infrastructure.NewsSourceMapper;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.SysUserMapper;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.idempotency.IdempotencyStore;
import cn.zhishi.stock.system.watchlist.WatchlistGroupMapper;
import cn.zhishi.stock.system.watchlist.WatchlistGroupRepository;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import cn.zhishi.stock.system.watchlist.WatchlistItemMapper;
import cn.zhishi.stock.system.watchlist.WatchlistItemRepository;
import cn.zhishi.stock.system.watchlist.WatchlistItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class BackendConfigurationTest {

  @Test
  void assemblesAuthenticationAndMarketUseCasesFromInfrastructurePorts() {
    new ApplicationContextRunner()
        .withUserConfiguration(BackendConfiguration.class)
        .withBean(SysUserMapper.class, () -> mock(SysUserMapper.class))
        .withBean(WatchlistGroupMapper.class, () -> mock(WatchlistGroupMapper.class))
        .withBean(WatchlistItemMapper.class, () -> mock(WatchlistItemMapper.class))
        .withBean(NewsSourceMapper.class, () -> mock(NewsSourceMapper.class))
        .withBean(NewsArticleMapper.class, () -> mock(NewsArticleMapper.class))
        .withBean(NewsRelationMapper.class, () -> mock(NewsRelationMapper.class))
        .withBean(AiTaskMapper.class, () -> mock(AiTaskMapper.class))
        .withBean(AiSessionMapper.class, () -> mock(AiSessionMapper.class))
        .withBean(AiMessageMapper.class, () -> mock(AiMessageMapper.class))
        .withBean(AiReportMapper.class, () -> mock(AiReportMapper.class))
        .withBean(AiFeedbackMapper.class, () -> mock(AiFeedbackMapper.class))
        .withBean(AiContextSnapshotMapper.class, () -> mock(AiContextSnapshotMapper.class))
        .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "stock.auth.jwt-secret=0123456789abcdef0123456789abcdef",
            "stock.market.scenario=NORMAL")
        .run(context -> {
          assertThat(context).hasSingleBean(AuthenticationService.class);
          assertThat(context).hasSingleBean(RefreshSessionService.class);
          assertThat(context).hasSingleBean(MarketOverviewQueryService.class);
          assertThat(context).hasSingleBean(SecurityQueryService.class);
          assertThat(context).hasSingleBean(SecurityMasterProvider.class);
          assertThat(context).hasSingleBean(SecurityQuoteProvider.class);
          assertThat(context).hasSingleBean(JwtAuthenticationFilter.class);
          assertThat(context).hasSingleBean(TraceIdFilter.class);
          assertThat(context).hasSingleBean(WatchlistGroupService.class);
          assertThat(context).hasSingleBean(WatchlistGroupRepository.class);
          assertThat(context).hasSingleBean(WatchlistItemService.class);
          assertThat(context).hasSingleBean(WatchlistItemRepository.class);
          assertThat(context).hasSingleBean(SecurityIdentityProvider.class);
          assertThat(context).hasSingleBean(SectorIdentityProvider.class);
          // 资讯域：采集侧与查询侧都要装起来，且查询用例同时充当 NewsCountProvider
          assertThat(context).hasSingleBean(NewsProvider.class);
          assertThat(context).hasSingleBean(RelationCatalogProvider.class);
          assertThat(context).hasSingleBean(NewsSourceStore.class);
          assertThat(context).hasSingleBean(NewsArticleStore.class);
          assertThat(context).hasSingleBean(NewsRelationStore.class);
          assertThat(context).hasSingleBean(NewsIngestionService.class);
          assertThat(context).hasSingleBean(NewsQueryService.class);
          assertThat(context.getBean(NewsQueryService.class))
              .isInstanceOf(cn.zhishi.stock.news.domain.NewsCountProvider.class);
          assertThat(context).hasSingleBean(IdempotencyStore.class);
          assertThat(context).hasSingleBean(IdempotencyGuard.class);

          // AI 域：M3-06 的预览 + M3-07 的编排。解析器必须只有一份，
          // 否则预览与创建会对同一份非法请求报出不同的业务码。
          assertThat(context).hasSingleBean(AiContextPreviewService.class);
          assertThat(context).hasSingleBean(AiTaskRequestResolver.class);
          assertThat(context).hasSingleBean(AiTaskService.class);
          assertThat(context).hasSingleBean(AiTaskStore.class);
          assertThat(context).hasSingleBean(AiSessionStore.class);
          assertThat(context).hasSingleBean(AiMessageStore.class);
          assertThat(context).hasSingleBean(AiReportStore.class);
          assertThat(context).hasSingleBean(AiContextSnapshotStore.class);
          assertThat(context).hasSingleBean(AiTaskQueue.class);
          assertThat(context).hasSingleBean(AiTaskEventStream.class);

          FilterRegistrationBean<?> registration =
              context.getBean("traceIdFilterRegistration", FilterRegistrationBean.class);
          assertThat(registration.getFilter()).isSameAs(context.getBean(TraceIdFilter.class));
          assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        });
  }
}
