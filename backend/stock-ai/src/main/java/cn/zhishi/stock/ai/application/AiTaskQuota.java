package cn.zhishi.stock.ai.application;

import java.time.OffsetDateTime;

/**
 * AI 当日配额与并发占用（契约 USER-07 的字段集，AI-03 的 {@code quota} 复用同一形状）。
 *
 * <h2>每一个数字都是真的</h2>
 * {@code usedCount} 是**数出来的**当日任务行数，不是预留位。
 * 它刻意不等于"今日实际模型调用次数"——那需要 {@code ai_usage} 聚合（M3-09）。
 * 宁可这个数与另一个口径暂时不同，也不编一个看起来合理的数：
 * 编出来的数字会让 M3-09 的收敛变成"改掉一个假值"，
 * 而改之前没有人能发现它是假的。
 *
 * <p>口径已记入已知问题：M3-09 引入 {@code ai_usage} 后必须显式决定
 * "一次用户意图算一次还是算两次（重试）"，并只保留一处。
 *
 * @param resetsAt 额度重置时刻。契约 §26.4 的示例是次日 00:05，
 *                 本轮按 {@code Asia/Shanghai} 自然日零点，不引入契约未定义的宽限窗口。
 */
public record AiTaskQuota(
        int dailyLimit,
        int usedCount,
        int remainingCount,
        int runningCount,
        int concurrentLimit,
        OffsetDateTime resetsAt) {

    public static AiTaskQuota of(
            int dailyLimit,
            int usedCount,
            int runningCount,
            int concurrentLimit,
            OffsetDateTime resetsAt) {
        return new AiTaskQuota(
                dailyLimit,
                usedCount,
                Math.max(0, dailyLimit - usedCount),
                runningCount,
                concurrentLimit,
                resetsAt);
    }
}
