package cn.zhishi.stock.system.job;

import java.util.Set;

/**
 * 任务标识常量：{@code job_name} 与 {@code handler_name}。
 *
 * <h2>为什么必须共享一份</h2>
 * 写 {@code job_execution_summary} 的有两个进程：{@code stock-job} 的定时采集器与
 * {@code stock-backend} 的人工触发路径。两处各写字面量时，"后台页面上看不到定时任务的记录"
 * 会成为一个没有任何报错的故障——因为名字只差一个字符，SQL 照样成功。
 *
 * <p>放在 {@code stock-system} 而不是 {@code stock-admin}：{@code stock-job} 需要它，
 * 而它不该依赖整个后台域（见 {@link JobExecutionStore} 的说明）。
 *
 * <h2>{@code handlerName} 取的是"最终调用的领域用例"</h2>
 * 定时触发与人工触发跑的是同一段逻辑，因此两者的 handler 必须同名，否则
 * "同一个任务的两种触发方式"在按 handler 聚合时会变成两个东西。
 * 本项目没有 XXL-JOB 的 Handler 注册表，因此这里用"承载逻辑的用例方法"来标识，
 * 它是唯一在两个进程里都成立的稳定名字。
 */
public final class JobNames {

    /** 行情总览采集。 */
    public static final String MARKET_OVERVIEW_COLLECT = "market-overview-collect";
    public static final String MARKET_OVERVIEW_HANDLER = "MarketIngestionService.collect";

    /** 资讯/公告增量采集。 */
    public static final String NEWS_INGEST = "news-ingest";
    public static final String NEWS_INGEST_HANDLER = "NewsIngestionService.ingest";

    /** 导出文件与作业记录的到期清理。 */
    public static final String EXPORT_RETENTION_SWEEP = "export-retention-sweep";
    public static final String EXPORT_RETENTION_HANDLER = "ExportRetentionSweeper.sweep";

    /**
     * 全部任务名。
     *
     * <p>人工触发与重试都要先确认"这个名字在白名单里"，否则拿到了数据库里的
     * 一个任意 {@code job_name} 就能让后台按名字去调它——白名单的意义正在于此。
     */
    public static final Set<String> ALL = Set.of(
            MARKET_OVERVIEW_COLLECT, NEWS_INGEST, EXPORT_RETENTION_SWEEP);

    private JobNames() {
    }
}
