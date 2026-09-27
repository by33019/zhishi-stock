package cn.zhishi.stock.backend.jobs;

import cn.zhishi.stock.admin.domain.JobTaskExecutor;
import cn.zhishi.stock.admin.domain.JobTrigger;
import cn.zhishi.stock.export.application.ExportRetentionSweeper;
import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.news.domain.NewsIngestionResult;
import cn.zhishi.stock.system.job.JobExecutionCounts;
import cn.zhishi.stock.system.job.JobExecutionOutcome;
import cn.zhishi.stock.system.job.JobNames;
import cn.zhishi.stock.system.job.NewsIngestionJobCounts;
import org.springframework.beans.factory.annotation.Value;

/**
 * 在 API 进程内执行白名单任务（契约 §16.3 ADM-JOB-02/05）。
 *
 * <h2>为什么不用 stock-job 的采集器</h2>
 * 那三个类住在 {@code stock-job}，而它们各自的 {@code @Component} 一旦被
 * API 进程扫描到，会让同一份装配链（行情 Store、资讯 Store、导出清理器）在两个进程里
 * 各存在一套。更根本的是：定时壳（{@code @Scheduled} + fixedDelay）与"现在执行一次"
 * 是两件事——前者关心"下一轮什么时候"、后者关心"这一次的结果"，
 * 混用一个类会让两边都变得不好读。
 *
 * <p>两条路径最终调用的都是同一个领域用例（handlerName 相同），
 * 因此在按 handler 聚合时"定时触发"与"人工触发"仍然是同一个任务。
 *
 * <h2>为什么行情采集不报告计数</h2>
 * {@code MarketIngestionService.collect} 返回的是聚合结果（指数、板块、榜单），
 * 而不是"采集了多少条原始行情"——后者是 Provider 内部的事。
 * 榜单行数、板块数都是派生量，把它们填进 {@code input_count} 会让这个字段
 * 在不同任务里表示不同的东西（{@code JobExecutionCounts} 的说明里写了这条判据）。
 * 因此这里返回"成功但没有计数"，页面会显示"计数未采集"而不是一排 0。
 */
public class InProcessJobRunner implements JobTaskExecutor {

    private final MarketIngestionService marketIngestion;
    private final NewsIngestionService newsIngestion;
    private final ExportRetentionSweeper exportSweeper;
    private final int sweepBatchSize;

    public InProcessJobRunner(
            MarketIngestionService marketIngestion,
            NewsIngestionService newsIngestion,
            ExportRetentionSweeper exportSweeper,
            @Value("${stock.export.sweep-batch-size:200}") int sweepBatchSize) {
        this.marketIngestion = marketIngestion;
        this.newsIngestion = newsIngestion;
        this.exportSweeper = exportSweeper;
        this.sweepBatchSize = sweepBatchSize;
    }

    @Override
    public JobExecutionOutcome execute(JobTrigger trigger) {
        return switch (trigger.jobName()) {
            case JobNames.MARKET_OVERVIEW_COLLECT -> collectMarketOverview(trigger);
            case JobNames.NEWS_INGEST -> ingestNews();
            case JobNames.EXPORT_RETENTION_SWEEP -> sweepExports();
            default -> throw new IllegalArgumentException(
                    "未知任务（应已被白名单挡住）：" + trigger.jobName());
        };
    }

    /** {@code scopeKey} 已经过白名单校验，因此这里可以直接当市场代码用。 */
    private JobExecutionOutcome collectMarketOverview(JobTrigger trigger) {
        marketIngestion.collect(trigger.scopeKey());
        return JobExecutionOutcome.success();
    }

    /** 计数映射与定时侧共用一份，避免两个入口报出不同的数字。 */
    private JobExecutionOutcome ingestNews() {
        NewsIngestionResult result = newsIngestion.ingest(null);
        return JobExecutionOutcome.success(NewsIngestionJobCounts.of(result));
    }

    /**
     * 清理类任务的计数：只报"删除了多少"。
     *
     * <p>{@code ExportRetentionSweeper.sweep(limit)} 的参数是"最多删这么多"，
     * 返回值是实际删除数，**不返回扫描量**（它逐条判定，不先数一遍）。
     * 因此 {@code input_count} 保持 0 而不是拿删除数去填——
     * 那会把"扫描了 2000 条、删了 5 条"说成"输入 5 条"。
     */
    private JobExecutionOutcome sweepExports() {
        int deleted = exportSweeper.sweep(sweepBatchSize);
        return JobExecutionOutcome.success(new JobExecutionCounts(0, 0, 0, 0, deleted));
    }
}
