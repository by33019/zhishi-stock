package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisAccessTokenBlacklist implements AccessTokenBlacklist {

    private static final String KEY_PREFIX = "auth:access:blacklist:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisAccessTokenBlacklist(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean contains(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }

    @Override
    public void add(String jti, Instant expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        if (!remaining.isNegative() && !remaining.isZero()) {
            redis.opsForValue().set(KEY_PREFIX + jti, "revoked", remaining);
        }
    }
}
