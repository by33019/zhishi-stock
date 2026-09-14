package cn.zhishi.stock.system.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisLoginAttemptStore implements LoginAttemptStore {

    private static final String KEY_PREFIX = "auth:login-attempt:";
    private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT =
            new DefaultRedisScript<>("""
                    local raw = redis.call('GET', KEYS[1])
                    local failures = 0
                    local lockedUntil = 0
                    if raw then
                      local separator = string.find(raw, ':', 1, true)
                      failures = tonumber(string.sub(raw, 1, separator - 1)) or 0
                      local lockedText = string.sub(raw, separator + 1)
                      if lockedText ~= '' then lockedUntil = tonumber(lockedText) or 0 end
                    end
                    local now = tonumber(ARGV[1])
                    if lockedUntil > now then return 1 end
                    if lockedUntil > 0 then failures = 0 end
                    failures = failures + 1
                    if failures >= tonumber(ARGV[2]) then
                      lockedUntil = now + tonumber(ARGV[3])
                    else
                      lockedUntil = 0
                    end
                    local lockedText = lockedUntil > 0 and tostring(lockedUntil) or ''
                    redis.call('PSETEX', KEYS[1], ARGV[4], tostring(failures) .. ':' .. lockedText)
                    return lockedUntil > 0 and 1 or 0
                    """, Long.class);

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
    public boolean recordFailure(
            long userId,
            Instant now,
            int maximumFailures,
            Duration lockDuration) {
        Long locked = redis.execute(
                RECORD_FAILURE_SCRIPT,
                List.of(key(userId)),
                Long.toString(now.toEpochMilli()),
                Integer.toString(maximumFailures),
                Long.toString(lockDuration.toMillis()),
                Long.toString(timeToLive.toMillis()));
        return Long.valueOf(1L).equals(locked);
    }

    @Override
    public void clear(long userId) {
        redis.delete(key(userId));
    }

    private String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
