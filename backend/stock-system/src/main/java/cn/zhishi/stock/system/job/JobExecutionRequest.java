package cn.zhishi.stock.system.job;

import java.time.OffsetDateTime;

/**
 * 一次执行的元数据（谁触发、哪个任务、第几次尝试）。由调用方描述，由
 * {@code JobExecutionRecorder} 补上执行 ID 与开始时刻后写库。
 *
 * <h2>为什么不在这里生成执行 ID</h2>
 * ID 由存储侧生成（{@code LongSupplier}），和执行体共用同一个生成器。
 * 让执行体也能造 ID，就会出现两处造 ID 的代码，而它们迟早会用不同的规则。
 *
 * <h2>{@code batchId} 的两种来源</h2>
 * 定时任务每轮新生成一个；重试沿用**原记录的批次**——{@code uk_job_execution_batch_shard_attempt}
 * 正是靠"同批同分片 + 递增 attempt_no"来保证重试不会写出两条同一次尝试的记录。
 *
 * <h2>为什么 {@code scheduledAt} 通常是 null</h2>
 * 本项目的定时任务用 {@code fixedDelay}：下一轮在上轮结束后才确定，因此不存在
 * "计划时刻"这个事实。拿实际开始时间去填它，会让"计划 vs 实际"这个最常看的
 * 排障维度从一开始就是假的。人工触发同理。
 */
public record JobExecutionRequest(
        String jobName,
        String handlerName,
        String batchId,
        Long providerId,
        JobTriggerType triggerType,
        int shardIndex,
        int shardTotal,
        int attemptNo,
        OffsetDateTime scheduledAt,
        String traceId) {

    public JobExecutionRequest {
        if (shardTotal < 1) {
            throw new IllegalArgumentException("shardTotal 必须 >= 1");
        }
        if (shardIndex < 0 || shardIndex >= shardTotal) {
            throw new IllegalArgumentException(
                    "shardIndex 必须落在 [0, shardTotal) 内："
                            + shardIndex + " / " + shardTotal);
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 从 1 开始");
        }
    }

    /** 定时调度触发：单分片、第一次尝试、无 Provider、无计划时刻。 */
    public static JobExecutionRequest scheduled(
            String jobName, String handlerName, String batchId, String traceId) {
        return new JobExecutionRequest(
                jobName, handlerName, batchId, null, JobTriggerType.SCHEDULED,
                0, 1, 1, null, traceId);
    }

    /** 管理员人工触发（ADM-JOB-02）：单分片、第一次尝试。 */
    public static JobExecutionRequest manual(
            String jobName,
            String handlerName,
            String batchId,
            Long providerId,
            int shardTotal,
            String traceId) {
        return new JobExecutionRequest(
                jobName, handlerName, batchId, providerId, JobTriggerType.MANUAL,
                0, shardTotal, 1, null, traceId);
    }

    /** 管理员重试（ADM-JOB-05）：沿用原批次与原分片，只递增尝试号。 */
    public static JobExecutionRequest retryOf(
            JobExecution previous, int attemptNo, String traceId) {
        return new JobExecutionRequest(
                previous.jobName(),
                previous.handlerName(),
                previous.batchId(),
                previous.providerId(),
                JobTriggerType.RETRY,
                previous.shardIndex(),
                previous.shardTotal(),
                attemptNo,
                null,
                traceId);
    }
}
