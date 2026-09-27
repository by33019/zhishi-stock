package cn.zhishi.stock.system.job;

import java.time.OffsetDateTime;

/**
 * 待插入的执行记录：请求 + 执行 ID + 开始时刻。
 *
 * <p>这三个字段合起来才是 {@code job_execution_summary} 的一行"开始"状态。
 * 分开传三个参数也能跑，但那时 {@code insert} 的签名里会混进
 * {@code request} 的十个字段，读起来分不清哪些是"谁触发"、哪些是"什么时候"。
 */
public record NewJobExecution(
        long executionId,
        JobExecutionRequest request,
        OffsetDateTime startedAt) {
}
