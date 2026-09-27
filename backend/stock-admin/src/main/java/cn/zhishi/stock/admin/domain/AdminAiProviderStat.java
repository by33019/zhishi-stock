package cn.zhishi.stock.admin.domain;

/**
 * AI Provider 维度的调用统计（ADM-AI-01 总览的 "Provider 状态"）。
 *
 * <p>平台没有 Provider 健康探测基础设施（ADM-PRV 未排期），
 * "状态"这里给出的是可观测的事实：调用次数与成功率——
 * 连续失败或成功率骤降的 Provider 会自己在这张表里现形。
 */
public record AdminAiProviderStat(
        String providerCode,
        String modelCode,
        long calls,
        Double successRate,
        Long avgFirstChunkLatencyMillis,
        Long avgTotalLatencyMillis) {
}
