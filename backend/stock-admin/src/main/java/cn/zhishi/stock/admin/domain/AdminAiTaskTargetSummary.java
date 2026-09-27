package cn.zhishi.stock.admin.domain;

/**
 * AI 任务的目标摘要（{@code ai_task_target} 的快照列）。
 *
 * @param targetRole PRIMARY / COMPARISON / CONTEXT——同一任务可有多类目标
 */
public record AdminAiTaskTargetSummary(
        String targetType,
        String targetCode,
        String targetName,
        String targetRole) {
}
