package cn.zhishi.stock.system.ratelimit;

/**
 * 超过限流基线（契约 §22.1：超过限流返回 HTTP 429）。
 *
 * <p>业务码 {@code RATE_LIMITED}；响应头由异常处理器按本异常携带的
 * 限额三元组设置（{@code RateLimit-Limit / Remaining / Reset} 与 {@code Retry-After}）。
 * 各域如需自己的业务码（如导出的 {@code EXPORT_RATE_LIMITED}），沿用各自的
 * 域内限流器即可——本异常服务跨域的通用拦截器。
 */
public class RateLimitExceededException extends RuntimeException {

    private final int limit;
    private final long resetEpochSeconds;
    private final long retryAfterSeconds;

    public RateLimitExceededException(
            int limit, long resetEpochSeconds, long retryAfterSeconds) {
        super("请求过于频繁，请在 " + retryAfterSeconds + " 秒后重试");
        this.limit = limit;
        this.resetEpochSeconds = resetEpochSeconds;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int limit() {
        return limit;
    }

    public long resetEpochSeconds() {
        return resetEpochSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
