package cn.zhishi.stock.ai.application;

/**
 * 202 受理响应（契约 §26.3）：AI-03 / AI-07 / AI-08 共用。
 *
 * <p>{@code statusUrl} 与 {@code streamUrl} 由服务端给出而不是让前端自己拼：
 * 路径形状属于契约，前端硬编码一份会在版本变更时静默失效——
 * 它只会表现为 404，而 404 看起来像"任务不存在"。
 */
public record AiTaskAccepted(
        AiTaskSummary task, String statusUrl, String streamUrl, AiTaskQuota quota) {
}
