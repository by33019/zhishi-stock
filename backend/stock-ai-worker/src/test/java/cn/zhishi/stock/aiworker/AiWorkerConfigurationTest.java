package cn.zhishi.stock.aiworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zhishi.stock.ai.application.AiTaskExecutionService;
import cn.zhishi.stock.ai.application.AiTaskRecoveryService;
import cn.zhishi.stock.ai.application.AiTaskService;
import cn.zhishi.stock.ai.domain.AiContentHasher;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import cn.zhishi.stock.ai.domain.AiEvidenceStore;
import cn.zhishi.stock.ai.domain.AiMessageStore;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import cn.zhishi.stock.ai.domain.LlmProviderPort;
import cn.zhishi.stock.ai.infrastructure.AiContextSnapshotMapper;
import cn.zhishi.stock.ai.infrastructure.AiEvidenceMapper;
import cn.zhishi.stock.ai.infrastructure.AiMessageMapper;
import cn.zhishi.stock.ai.infrastructure.AiReportMapper;
import cn.zhishi.stock.ai.infrastructure.AiTaskMapper;
import cn.zhishi.stock.market.application.MarketOverviewQueryService;
import cn.zhishi.stock.market.domain.QuoteSnapshotBatchProvider;
import cn.zhishi.stock.market.domain.SectorIdentityProvider;
import cn.zhishi.stock.market.domain.SectorProvider;
import cn.zhishi.stock.market.domain.SecurityIdentityProvider;
import cn.zhishi.stock.news.domain.NewsArticleStore;
import cn.zhishi.stock.news.domain.NewsEvidenceProvider;
import cn.zhishi.stock.news.domain.NewsSourceStore;
import cn.zhishi.stock.news.infrastructure.NewsArticleMapper;
import cn.zhishi.stock.news.infrastructure.NewsRelationMapper;
import cn.zhishi.stock.news.infrastructure.NewsSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 执行器的装配测试。
 *
 * <p>缺任何一个 Bean 的症状都不是"启动报错"，而是**任务永远完不成**：
 * 执行器拿不到取数来源 → 上下文构建失败 → 任务以 {@code AI_CORE_DATA_MISSING} 失败，
 * 而那条错误信息指向的是"行情缺失"，看不出是装配少了一个 Bean。
 * 所以这里逐个断言"取数链路是完整的"。
 */
class AiWorkerConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(AiWorkerConfiguration.class)
                    .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(AiTaskMapper.class, () -> mock(AiTaskMapper.class))
                    .withBean(AiMessageMapper.class, () -> mock(AiMessageMapper.class))
                    .withBean(AiReportMapper.class, () -> mock(AiReportMapper.class))
                    .withBean(AiEvidenceMapper.class, () -> mock(AiEvidenceMapper.class))
                    .withBean(AiContextSnapshotMapper.class, () -> mock(AiContextSnapshotMapper.class))
                    .withBean(NewsSourceMapper.class, () -> mock(NewsSourceMapper.class))
                    .withBean(NewsArticleMapper.class, () -> mock(NewsArticleMapper.class))
                    .withBean(NewsRelationMapper.class, () -> mock(NewsRelationMapper.class));

    @Test
    void assemblesTheAiPersistencePorts() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AiTaskStore.class);
            assertThat(context).hasSingleBean(AiContextSnapshotStore.class);
            assertThat(context).hasSingleBean(AiMessageStore.class);
            assertThat(context).hasSingleBean(AiReportStore.class);
            // 少这一个的症状不是启动报错，而是**报告写下了、来源一行没有**：
            // 用户点开引用栏看到空列表，与"这份报告确实没有引用"无法区分。
            assertThat(context).hasSingleBean(AiEvidenceStore.class);
        });
    }

    /**
     * 取数链路必须完整——这是"报告里的数字与页面上的数字一致"的前提。
     *
     * <p>{@code NewsEvidenceProvider} 由 {@code NewsQueryService} 兼任，
     * 所以这里断言的是接口可解析，而不是某个具体类。
     */
    @Test
    void assemblesTheWholeContextChain() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(QuoteSnapshotBatchProvider.class);
            assertThat(context).hasSingleBean(MarketOverviewQueryService.class);
            assertThat(context).hasSingleBean(SectorProvider.class);
            assertThat(context).hasSingleBean(SecurityIdentityProvider.class);
            assertThat(context).hasSingleBean(SectorIdentityProvider.class);
            assertThat(context).hasSingleBean(NewsSourceStore.class);
            assertThat(context).hasSingleBean(NewsArticleStore.class);
            assertThat(context).hasSingleBean(NewsEvidenceProvider.class);
            assertThat(context).hasSingleBean(AiContentHasher.class);
            assertThat(context).hasSingleBean(AiContextBuilder.class);
        });
    }

    @Test
    void assemblesTheExecutorAndTheRecoveryScan() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AiTaskQueue.class);
            assertThat(context).hasSingleBean(AiTaskEventStream.class);
            assertThat(context).hasSingleBean(LlmProviderPort.class);
            assertThat(context).hasSingleBean(AiTaskExecutionService.class);
            assertThat(context).hasSingleBean(AiTaskRecoveryService.class);
        });
    }

    /**
     * 刻意**不**装配创建任务的用例。
     *
     * <p>{@code AiTaskService} 是 Web 侧的东西：它要并发闸门、每日额度、
     * 会话与目标解析，还要往队列里投递。把它拖进执行器会让这个进程持有一份
     * 永远不会被调用的编排逻辑——而"哪一侧负责投递"也就有了两处答案。
     */
    @Test
    void doesNotAssembleTheWebSideUseCase() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AiTaskService.class));
    }
}
