package cn.zhishi.stock.admin.domain;

import java.util.List;

/**
 * AI 运营总览（契约 §19 ADM-AI-01）。
 *
 * <h2>每个数字都有唯一出处</h2>
 * <ul>
 *   <li>任务量与状态分布来自 {@code ai_task} 按状态分组计数；</li>
 *   <li>队列长度 = {@code QUEUED} 行数（已入队未被 worker 认领）；
 *       运行中 = {@code PREPARING} / {@code RUNNING} / {@code VALIDATING}
 *       （已被认领、未到终态）——"卡死的任务"正是这两组数的差值异常；</li>
 *   <li>受限报告数 = {@code ai_report.is_limited = 1} 的行数（质量闸门降级的产出）；</li>
 *   <li>Token 与估算成本来自 {@code ai_usage} 汇总（成本恒为 0：平台尚无价格表，
 *       列存在但数值不编造）；</li>
 *   <li>耗时分位数在服务层由用量明细计算（{@code ai_usage} 的毫秒列）。</li>
 * </ul>
 *
 * @param firstChunkLatencyP50Millis 首段耗时的 p50；窗口内没有成功调用时为 {@code null}
 */
public record AdminAiOverview(
        long taskCount,
        long succeededCount,
        long failedCount,
        long canceledCount,
        long timedOutCount,
        Double successRate,
        long restrictedReportCount,
        long queuedCount,
        long runningCount,
        Long firstChunkLatencyP50Millis,
        Long firstChunkLatencyP95Millis,
        Long totalLatencyP50Millis,
        Long totalLatencyP95Millis,
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long totalTokens,
        java.math.BigDecimal estimatedCost,
        List<AdminAiProviderStat> providers) {

    public AdminAiOverview {
        providers = List.copyOf(providers);
    }
}
