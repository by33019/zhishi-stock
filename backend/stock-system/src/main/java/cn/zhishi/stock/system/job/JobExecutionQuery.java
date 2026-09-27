package cn.zhishi.stock.system.job;

import java.time.OffsetDateTime;

/**
 * 任务执行记录的查询条件（契约 §16.3 ADM-JOB-03）。
 *
 * <h2>时间范围的口径沿用 LOG-01</h2>
 * 契约对 ADM-JOB-03 只写了"时间范围"，没写缺省与上限。这里采用与操作日志
 * 完全相同的 7 天 / 90 天（{@code OperationLogQuery}）：
 *
 * <ul>
 *   <li>不给缺省时，采集任务每分钟一条、三个任务一年就有约 150 万行，
 *       一次"打开页面"会变成全表扫描，而 {@code idx_job_execution_name_time}
 *       恰恰是为"任务 + 时间"这个组合建的；</li>
 *   <li>两处用不同数字，就会有人问"为什么这里能查 30 天、那里只能 7 天"，
 *       而答案只是当初谁先写。</li>
 * </ul>
 *
 * <p>与操作日志同样的取舍：补缺省需要 {@code Clock}，因此构造器只做与时间无关的夹取，
 * 时间范围由 {@code JobAdminService} 补齐后经 {@link #withRange} 传回。
 */
public record JobExecutionQuery(
        String jobName,
        Long providerId,
        JobExecutionStatus status,
        JobTriggerType triggerType,
        String batchId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int page,
        int size) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_RANGE_DAYS = 7;
    public static final int MAX_RANGE_DAYS = 90;

    public JobExecutionQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public int offset() {
        return (page - 1) * size;
    }

    public JobExecutionQuery withRange(OffsetDateTime start, OffsetDateTime end) {
        return new JobExecutionQuery(
                jobName, providerId, status, triggerType, batchId, start, end, page, size);
    }
}
