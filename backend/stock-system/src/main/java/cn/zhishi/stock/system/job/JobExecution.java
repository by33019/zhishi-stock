package cn.zhishi.stock.system.job;

import java.time.OffsetDateTime;

/**
 * 一条任务执行记录（契约 §16.3 ADM-JOB-03 与 ADM-JOB-04 的字段合集）。
 *
 * <h2>为什么列表与详情共用一个类型</h2>
 * 契约里详情的字段是列表的**超集**，两者的差异只在"调用方要看多少"。
 * 拆成两个类型就要两套行映射，而下次加一列时极容易只在其中一个上实现——
 * 症状是"列表里有、详情里没有"或反之。列表页一页 20 行，多带几个短字段的代价
 * 远小于两套映射长期分叉的代价。
 *
 * <h2>{@code counts} 为 null 的含义</h2>
 * 表示**这次执行没有采集计数**，不是"处理了 0 条"。判据来自
 * {@code job_execution_summary.counts_available}（见 V10 的说明）——
 * 不能从五个计数自身推出，因为全是 0 是合法取值。
 *
 * <h2>{@code errorSummary} 已经脱敏</h2>
 * 契约要求"不返回第三方完整原始响应"，裁剪由执行体负责（记录器不知道什么算第三方）。
 *
 * @param executionId      执行记录 ID
 * @param jobName          稳定业务任务名
 * @param handlerName      执行体名称（排障时用于定位是哪一段代码）
 * @param batchId          同次任务各分片共享的批次 ID；重试沿用原批次
 * @param providerId       关联外部 Provider ID；本平台尚无 Provider 元数据时为 null
 * @param triggerType      触发方式
 * @param status           执行状态
 * @param shardIndex       分片序号（从 0 开始）
 * @param shardTotal       分片总数
 * @param attemptNo        尝试次数（重试递增，从 1 开始）
 * @param scheduledAt      计划执行时刻；人工触发时为 null
 * @param startedAt        实际开始时刻
 * @param completedAt      完成时刻；仍在运行时为 null
 * @param sourceDataStartAt 源数据窗口起点；无此概念时为 null
 * @param sourceDataEndAt  源数据窗口终点；无此概念时为 null
 * @param counts           处理计数；未采集为 null
 * @param errorCategory    错误类别（用于聚合统计，如 PROVIDER_TIMEOUT）
 * @param errorCode        错误码
 * @param errorSummary     脱敏后的错误摘要
 * @param traceId          链路追踪 ID
 * @param createdAt        记录创建时刻
 * @param updatedAt        记录更新时刻
 */
public record JobExecution(
        long executionId,
        String jobName,
        String handlerName,
        String batchId,
        Long providerId,
        JobTriggerType triggerType,
        JobExecutionStatus status,
        int shardIndex,
        int shardTotal,
        int attemptNo,
        OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime sourceDataStartAt,
        OffsetDateTime sourceDataEndAt,
        JobExecutionCounts counts,
        String errorCategory,
        String errorCode,
        String errorSummary,
        String traceId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 供前端判断是否渲染计数区；为 {@code false} 时页面写"计数未采集"。 */
    public boolean countsAvailable() {
        return counts != null;
    }

    /** 是否能被重试（契约 ADM-JOB-05）。 */
    public boolean retryable() {
        return status.isRetryable();
    }
}
