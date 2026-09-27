package cn.zhishi.stock.system.ratelimit;

/**
 * 一次限流判定的结果。
 *
 * @param allowed           是否放行
 * @param limit             窗口上限（响应头 {@code RateLimit-Limit}）
 * @param remaining         窗口内剩余额度（{@code RateLimit-Remaining}）
 * @param resetEpochSeconds 窗口重置时刻的epoch秒（{@code RateLimit-Reset}）
 * @param retryAfterSeconds 超限时建议的等待秒数（{@code Retry-After}）；未超限为 0
 */
public record RateLimitDecision(
        boolean allowed,
        int limit,
        long remaining,
        long resetEpochSeconds,
        long retryAfterSeconds) {
}
