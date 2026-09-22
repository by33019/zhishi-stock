package cn.zhishi.stock.ai.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * AI 当日配额与并发占用（契约 USER-07 的字段集，AI-03 的 {@code quota} 复用同一形状）。
 *
 * <h2>每一个数字都是真的</h2>
 * {@code usedCount} 是**数出来的**当日任务行数，不是预留位。
 *
 * <h2>M3-09 定下的口径：额度按「任务」扣，用量按「调用」记</h2>
 * 两个数字刻意不等，且各自只有一处产出（{@link AiQuotaQueryService} 与
 * {@code ai_usage}）：
 * <ul>
 *   <li>额度在**创建时**扣减（创建前的额度闸门）。若改成"按调用成功计"，
 *       用户可以让任务反复失败来绕过额度，而每一次失败都真实消耗了供应商调用。</li>
 *   <li>同一任务内的自动重试不额外扣额度——它是系统重试，不是新的用户意图；
 *       但它产生的每一次调用都会在 {@code ai_usage} 里各记一行。</li>
 *   <li>用户主动重试（AI-07）与追问（AI-08）创建**新任务行**，各自扣一次额度。</li>
 * </ul>
 * 契约 USER-07 说"实际 Provider 失败不计成功次数"——那句话说明 {@code usedCount}
 * 不是"调用成功次数"；调用成功次数由 {@code ai_usage.result_status} 回答。
 * 两条口径都保留，互不替代。
 *
 * @param date     这份配额属于哪一天。没有它，客户端只能从 {@code resetsAt} 反推，
 *                 而跨零点的那一秒里反推得到的是前一天。
 * @param resetsAt 额度重置时刻。契约 §26.4 的示例是次日 00:05，
 *                 本轮按 {@code Asia/Shanghai} 自然日零点，不引入契约未定义的宽限窗口。
 */
public record AiTaskQuota(
        LocalDate date,
        int dailyLimit,
        int usedCount,
        int remainingCount,
        int runningCount,
        int concurrentLimit,
        OffsetDateTime resetsAt) {

    public static AiTaskQuota of(
            LocalDate date,
            int dailyLimit,
            int usedCount,
            int runningCount,
            int concurrentLimit,
            OffsetDateTime resetsAt) {
        return new AiTaskQuota(
                date,
                dailyLimit,
                usedCount,
                Math.max(0, dailyLimit - usedCount),
                runningCount,
                concurrentLimit,
                resetsAt);
    }
}
