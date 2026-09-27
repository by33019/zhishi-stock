package cn.zhishi.stock.job;

import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.system.job.JobExecutionOutcome;
import cn.zhishi.stock.system.job.JobExecutionRecorder;
import cn.zhishi.stock.system.job.JobExecutionRequest;
import cn.zhishi.stock.system.job.JobIdentifiers;
import cn.zhishi.stock.system.job.JobNames;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行情总览采集（每 60 秒）。
 *
 * <h2>为什么要包一层执行记录</h2>
 * 后台的任务执行历史页读的是 {@code job_execution_summary}，而这张表此前零写入方。
 * 只让"人工触发"写记录、定时任务不写，会出现最讽刺的一种情况：
 * 页面上只有管理员手动跑的几次，日常真正在跑的调度一条都看不到，
 * 于是"任务最近一次成功是什么时候"永远答不出来。
 *
 * <h2>为什么计数是空的</h2>
 * {@link MarketIngestionService#collect(String)} 返回的是聚合结果，不是"采集了多少条原始行情"。
 * 用榜单行数之类去填 {@code input_count} 会让这个字段在不同任务里表示不同的东西，
 * 因此这里返回"成功但没有计数"，页面显示"计数未采集"。
 * 与 {@code InProcessJobRunner}（人工触发）口径一致——两条路径必须说同一种话。
 */
@Component
public class ScheduledMarketCollector {

    private final MarketIngestionService ingestion;
    private final JobExecutionRecorder executions;

    public ScheduledMarketCollector(
            MarketIngestionService ingestion, JobExecutionRecorder executions) {
        this.ingestion = ingestion;
        this.executions = executions;
    }

    @Scheduled(
            initialDelayString = "${stock.market.collect-initial-delay-ms:1000}",
            fixedDelayString = "${stock.market.collect-delay-ms:60000}")
    public void collect() {
        executions.record(
                JobExecutionRequest.scheduled(
                        JobNames.MARKET_OVERVIEW_COLLECT,
                        JobNames.MARKET_OVERVIEW_HANDLER,
                        JobIdentifiers.newBatchId(),
                        JobIdentifiers.scheduledTraceId()),
                () -> {
                    ingestion.collect("CN");
                    return JobExecutionOutcome.success();
                });
    }
}
