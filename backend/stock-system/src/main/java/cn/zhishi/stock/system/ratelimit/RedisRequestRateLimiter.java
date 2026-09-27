package cn.zhishi.stock.system.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RequestRateLimiter} 的 Redis 固定窗口实现（与导出域限流同一套语义）。
 *
 * <h2>固定窗口与桶键</h2>
 * 窗口对齐到 {@code window} 的整数倍边界（epoch/window 向下取整），
 * 桶键 = {@code rate:{key}:{bucketStart}}。窗口边界由**键名**决定，
 * TTL 只用于兜底清理，不可能把窗口往后推。
 * 已知代价是边界处可能连过 2 倍额度（上一窗口末尾 + 本窗口开头）；
 * 对"防误点/防滥用"的阀门可以接受，收益是判定逻辑短到可以被完整读懂。
 *
 * <h2>Redis 异常放行</h2>
 * 限流是保护措施，不是业务事实——Redis 抖动时放行并告警，
 * 与导出域限流的降级口径一致。
 */
public class RedisRequestRateLimiter implements RequestRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRequestRateLimiter.class);

    static final String KEY_PREFIX = "rate:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRequestRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public RateLimitDecision acquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        long windowSeconds = window.toSeconds();
        long bucketStart = (now.getEpochSecond() / windowSeconds) * windowSeconds;
        long resetEpochSeconds = bucketStart + windowSeconds;
        String bucketKey = KEY_PREFIX + key + ":" + bucketStart;

        Long count;
        try {
            count = redis.opsForValue().increment(bucketKey);
            if (count != null && count == 1) {
                redis.expire(bucketKey, window.plusSeconds(5));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("限流计数失败，本次放行：key={}", key, exception);
            return new RateLimitDecision(true, limit, limit, resetEpochSeconds, 0);
        }
        if (count == null) {
            LOGGER.warn("限流计数未返回结果，本次放行：key={}", key);
            return new RateLimitDecision(true, limit, limit, resetEpochSeconds, 0);
        }

        long used = count;
        long remaining = Math.max(0, limit - used);
        if (used > limit) {
            return new RateLimitDecision(false, limit, 0, resetEpochSeconds,
                    Math.max(1, resetEpochSeconds - now.getEpochSecond()));
        }
        return new RateLimitDecision(true, limit, remaining, resetEpochSeconds, 0);
    }
}
