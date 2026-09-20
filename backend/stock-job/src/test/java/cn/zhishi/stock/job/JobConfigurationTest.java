package cn.zhishi.stock.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsProvider;
import cn.zhishi.stock.news.domain.NewsRelationStore;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.domain.RelationCatalogProvider;
import cn.zhishi.stock.news.infrastructure.NewsArticleMapper;
import cn.zhishi.stock.news.infrastructure.NewsRelationMapper;
import cn.zhishi.stock.news.infrastructure.NewsSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class JobConfigurationTest {

    /**
     * 采集任务要落库，因此资讯的三个 Mapper 必须在这里被替换成桩。
     *
     * <p>注意这里**验不到** {@code @MapperScan}：{@code ApplicationContextRunner}
     * 直接注册配置类，不会走扫描。扫描范围由 {@link StockJobApplicationTest} 钉住。
     */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(JobConfiguration.class)
                    .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(NewsSourceMapper.class, () -> mock(NewsSourceMapper.class))
                    .withBean(NewsArticleMapper.class, () -> mock(NewsArticleMapper.class))
                    .withBean(NewsRelationMapper.class, () -> mock(NewsRelationMapper.class))
                    .withPropertyValues("stock.market.scenario=NORMAL");

    @Test
    void assemblesMarketIngestionUseCase() {
        runner.run(context -> assertThat(context).hasSingleBean(MarketIngestionService.class));
    }

    /**
     * 缺任何一个 Bean 的症状都是"资讯永远是空的"——不会报错指向这里，
     * 因为定时任务只会在日志里静默地什么都不做。
     */
    @Test
    void assemblesNewsIngestionUseCase() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(NewsProvider.class);
                    assertThat(context).hasSingleBean(NewsSourceStore.class);
                    assertThat(context).hasSingleBean(NewsArticleStore.class);
                    assertThat(context).hasSingleBean(NewsRelationStore.class);
                    assertThat(context).hasSingleBean(RelationCatalogProvider.class);
                    assertThat(context).hasSingleBean(NewsIngestionService.class);
                });
    }

    /**
     * 采集端的资讯 Provider 与关联目录必须投影自**同一份**行情域主数据，
     * 否则"稿件里出现的证券名"与"关联表里的代理键"会来自两套名字表，
     * 而两套名字表的分歧不会报错，只会让关联静默地挂到不存在的标的上。
     */
    @Test
    void buildsTheNewsProviderAndRelationCatalogFromTheMarketProviders() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(cn.zhishi.stock.market.domain.SecurityMasterProvider.class);
                    assertThat(context).hasSingleBean(cn.zhishi.stock.market.domain.SectorProvider.class);
                    assertThat(context).hasSingleBean(cn.zhishi.stock.market.domain.SecurityIdentityProvider.class);
                    assertThat(context).hasSingleBean(cn.zhishi.stock.market.domain.SectorIdentityProvider.class);
                });
    }

    /** 采集器本身也要能被装配出来——否则应用起不来，或者采集永远不跑。 */
    @Test
    void assemblesTheNewsCollector() {
        runner.withUserConfiguration(ScheduledNewsCollector.class)
                .run(context -> assertThat(context).hasSingleBean(ScheduledNewsCollector.class));
    }
}
