package cn.zhishi.stock.export.infrastructure;

import cn.zhishi.stock.export.application.ExportException;
import cn.zhishi.stock.export.domain.ExportRateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 导出限流的 Redis 实现（契约 §9.2：榜单导出 2 次/分钟，维度为用户）。
 *
 * <h2>为什么用固定窗口计数，而不是滑动窗口</h2>
 * 契约给的是"2 次/分钟"。固定窗口把它实现成"每个自然分钟桶最多 2 次"，
 * 用一次 {@code INCR} 就能判定，且**不需要记录每次调用的时间戳**（那要么是 ZSET，
 * 要么是 Lua 脚本）。它的已知代价是边界处可能连过 4 次（上一分钟末尾 2 次 + 这一分钟开头 2 次）
 * ——对一个"防止误连点刷爆导出队列"的阀门来说，这个代价可以接受；
 * 而它的收益是判定逻辑短到可以被完整读懂。
 *
 * <h2>为什么桶名里带分钟号</h2>
 * 桶名带分钟号之后，窗口边界由**键名**决定，而不是由 TTL 决定。
 * 于是每次 {@code INCR} 都顺手 {@code EXPIRE} 是安全的：
 * 它不可能把窗口往后推（键一旦跨到下一分钟就是另一个键了），
 * 只用来兜底"上一次 INCR 之后进程退出、TTL 没设上"留下的永久键。
 *
 * <h2>计数发生在真正创建作业时</h2>
 * 调用点在用例层、幂等回放之后（见 {@code ExportJobController}），
 * 因此"同一个 Idempotency-Key 重放"不会重复扣次数——那本来就是同一次用户意图。
 */
public class RedisExportRateLimiter implements ExportRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisExportRateLimiter.class);

    /** 计数器键前缀。属于实现细节，不进契约。 */
    static final String BUCKET_KEY_PREFIX = "export:rate:";

    private final StringRedisTemplate redis;
    private final int limit;
    private final Duration window;
    private final Clock clock;

    public RedisExportRateLimiter(
            StringRedisTemplate redis, int limit, Duration window, Clock clock) {
        this.redis = redis;
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public void acquire(long userId) {
        String key = bucketKey(userId);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // INCR 的返回值理论上非空。真为空说明连接层出了问题，
            // 此时**放行**比拦下更安全：限流是保护措施，不是业务事实。
            LOGGER.warn("导出限流计数未返回结果，本次放行：userId={}", userId);
            return;
        }
        redis.expire(key, window);
        if (count > limit) {
            throw ExportException.rateLimited();
        }
    }

    private String bucketKey(long userId) {
        long bucket = Math.floorDiv(clock.instant().getEpochSecond(), window.toSeconds());
        return BUCKET_KEY_PREFIX + userId + ":" + bucket;
    }
}
