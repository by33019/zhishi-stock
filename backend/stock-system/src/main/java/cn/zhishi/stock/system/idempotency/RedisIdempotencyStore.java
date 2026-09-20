package cn.zhishi.stock.system.idempotency;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis 的幂等键存储，TTL = {@link IdempotencyGuard#WINDOW}。
 *
 * <p>只存一条 JSON 文本，不做任何解析——解析由 {@link IdempotencyGuard} 负责，
 * 这样"请求体比对"与"响应回放"的口径只有一处。
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final Duration window;

    public RedisIdempotencyStore(StringRedisTemplate redis, Duration window) {
        this.redis = redis;
        this.window = window;
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, long userId, String key) {
        String stored = redis.opsForValue().get(redisKey(scope, userId, key));
        if (stored == null || stored.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = stored.split("\u0000", 2);
        if (parts.length != 2) {
            // 残缺记录：当作没有，宁可多执行一次也不回放一个不完整的结果。
            return Optional.empty();
        }
        return Optional.of(new IdempotencyRecord(parts[0], parts[1]));
    }

    @Override
    public void save(String scope, long userId, String key, IdempotencyRecord record) {
        redis.opsForValue().set(
                redisKey(scope, userId, key),
                record.requestBody() + "\u0000" + record.responseJson(),
                window);
    }

    private String redisKey(String scope, long userId, String key) {
        return KEY_PREFIX + scope + ":" + userId + ":" + key;
    }
}
