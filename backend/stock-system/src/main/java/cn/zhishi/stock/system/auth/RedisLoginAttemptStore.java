package cn.zhishi.stock.system.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisLoginAttemptStore implements LoginAttemptStore {

    private static final String KEY_PREFIX = "auth:login-attempt:";

    private final StringRedisTemplate redis;
    private final Duration timeToLive;

    public RedisLoginAttemptStore(StringRedisTemplate redis, Duration timeToLive) {
        this.redis = redis;
        this.timeToLive = timeToLive;
    }

    @Override
    public Optional<LoginAttemptState> find(long userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(":", -1);
        Instant lockedUntil = parts.length > 1 && !parts[1].isBlank()
                ? Instant.ofEpochMilli(Long.parseLong(parts[1]))
                : null;
        return Optional.of(new LoginAttemptState(Integer.parseInt(parts[0]), lockedUntil));
    }

    @Override
    public void save(long userId, LoginAttemptState state) {
        String lockedUntil = state.lockedUntil() == null
                ? ""
                : Long.toString(state.lockedUntil().toEpochMilli());
        redis.opsForValue().set(
                key(userId), state.failures() + ":" + lockedUntil, timeToLive);
    }

    @Override
    public void clear(long userId) {
        redis.delete(key(userId));
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
