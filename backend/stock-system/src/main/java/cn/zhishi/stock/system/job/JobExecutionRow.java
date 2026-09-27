package cn.zhishi.stock.system.job;

import java.time.LocalDateTime;

/**
 * {@code job_execution_summary} 的一行（原始值，枚举与计数未解释）。
 *
 * <p>枚举列读成 {@code String} 而不是枚举：MyBatis 默认的 {@code EnumTypeHandler}
 * 按 {@code name()} 转换，读的时候若遇到库里没有的值会静默给 {@code null}，
 * 而非法状态正是这一行最需要被看见的部分。转换集中在
 * {@link MyBatisJobExecutionStore}，未知值在那里抛异常。
 */
public record JobExecutionRow(
        long executionId,
        String jobName,
        String handlerName,
        String batchId,
        Long providerId,
        String triggerType,
        String status,
        int shardIndex,
        int shardTotal,
        int attemptNo,
        LocalDateTime scheduledAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime sourceDataStartAt,
        LocalDateTime sourceDataEndAt,
        long inputCount,
        long successCount,
        long ignoredCount,
        long failureCount,
        long outputCount,
        boolean countsAvailable,
        String errorCategory,
        String errorCode,
        String errorSummary,
        String traceId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 五个计数只在"已采集"时才有意义；否则调用方应当给用户看"未采集"。 */
    public JobExecutionCounts countsOrNull() {
        return countsAvailable
                ? new JobExecutionCounts(
                        inputCount, successCount, ignoredCount, failureCount, outputCount)
                : null;
    }
}
