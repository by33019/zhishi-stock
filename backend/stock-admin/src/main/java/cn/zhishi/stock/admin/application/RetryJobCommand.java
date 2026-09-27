package cn.zhishi.stock.admin.application;

/**
 * 重试一次失败执行的请求体（契约 §16.3 ADM-JOB-05）。
 *
 * <p>{@code reason} 可选，但会进审计摘要——"谁在什么时候重试了什么"
 * 如果只有一个人名没有原因，第二天没人解释得清这次重试是为了验证修复还是手滑。
 */
public record RetryJobCommand(String reason) {
}
