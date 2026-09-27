package cn.zhishi.stock.system.job;

import java.util.UUID;

/**
 * 执行记录的两个标识：批次 ID 与链路追踪 ID。
 *
 * <h2>为什么批次 ID 是随机 UUID 而不是"任务名 + 时间戳"</h2>
 * 时间戳形式的批次在"同一秒内触发两次"时会撞车，而 {@code batch_id} 参与
 * {@code uk_job_execution_batch_shard_attempt} 唯一索引——撞车的表现是
 * 第二次触发插入失败（{@code DuplicateKeyException}），而不是两条独立记录。
 * 人工触发完全可能被连点两次，定时任务也确实可能在同一秒内被恢复扫描再次入队。
 *
 * <h2>为什么追踪 ID 带前缀</h2>
 * 定时任务没有 HTTP 请求，因此没有 {@code TraceIdFilter} 生成的 traceId。
 * 前缀（{@code sched-}）让"这条日志来自调度还是来自一次请求"在 grep 时一眼可辨，
 * 而两者会混在同一个 {@code sys_log} 与同一批应用日志里。
 *
 * <p>长度控制在 64 字符内（{@code trace_id varchar(64)}）：前缀 + UUID 共约 42 字符。
 */
public final class JobIdentifiers {

    private static final String SCHEDULED_PREFIX = "sched-";

    private JobIdentifiers() {
    }

    public static String newBatchId() {
        return UUID.randomUUID().toString();
    }

    /** 定时调度的追踪 ID。人工触发用请求自带的 traceId，不在这里生成。 */
    public static String scheduledTraceId() {
        return SCHEDULED_PREFIX + UUID.randomUUID();
    }
}
