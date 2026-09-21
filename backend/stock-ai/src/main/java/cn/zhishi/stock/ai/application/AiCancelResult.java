package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiTaskStatus;

/**
 * AI-06 取消结果（契约 §13.2）。
 *
 * <p>刻意**不带**完整的任务对象：取消是一个**意图**，不是一次状态变更。
 * 返回完整任务会让前端以为状态已经变了（并据此刷新界面），
 * 而实际生效要等 Worker 在下一个检查点兑现。
 *
 * @param cancelRequested      是否已记录取消意图（终态任务为 {@code false}）
 * @param effectiveImmediately 是否立刻生效；对已完成任务恒为 {@code false}
 */
public record AiCancelResult(
        String taskId, AiTaskStatus status, boolean cancelRequested, boolean effectiveImmediately) {
}
