package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisRefreshSessionStore implements RefreshSessionStore {

    private static final String TOKEN_PREFIX = "auth:refresh:token:";
    private static final String FAMILY_PREFIX = "auth:refresh:family:";

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration replayRetention;

    public RedisRefreshSessionStore(
            StringRedisTemplate redis,
            Clock clock,
            Duration replayRetention) {
        this.redis = redis;
        this.clock = clock;
        this.replayRetention = replayRetention;
    }

    @Override
    public Optional<RefreshTokenRecord> findByTokenHash(String tokenHash) {
        Map<Object, Object> values = redis.opsForHash().entries(tokenKey(tokenHash));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RefreshTokenRecord(
                tokenHash,
                value(values, "familyId"),
                Long.parseLong(value(values, "userId")),
                Instant.ofEpochMilli(Long.parseLong(value(values, "expiresAt"))),
                RefreshTokenRecord.Status.valueOf(value(values, "status"))));
    }

    @Override
    public void save(RefreshTokenRecord record) {
        String tokenKey = tokenKey(record.tokenHash());
        Map<String, String> values = new HashMap<>();
        values.put("familyId", record.familyId());
        values.put("userId", Long.toString(record.userId()));
        values.put("expiresAt", Long.toString(record.expiresAt().toEpochMilli()));
        values.put("status", record.status().name());
        redis.<String, String>opsForHash().putAll(tokenKey, values);
        Duration ttl = ttl(record.expiresAt());
        redis.expire(tokenKey, ttl);
        String familyKey = familyKey(record.familyId());
        redis.opsForSet().add(familyKey, record.tokenHash());
        redis.expire(familyKey, ttl);
    }

    @Override
    public void rotate(RefreshTokenRecord previous, RefreshTokenRecord next) {
        save(previous.withStatus(RefreshTokenRecord.Status.ROTATED));
        save(next);
    }

    @Override
    public void revokeFamily(String familyId) {
        Set<String> tokenHashes = redis.opsForSet().members(familyKey(familyId));
        if (tokenHashes == null) {
            return;
        }
        tokenHashes.forEach(tokenHash -> redis.<String, String>opsForHash()
                .put(tokenKey(tokenHash), "status", RefreshTokenRecord.Status.REVOKED.name()));
    }

    private Duration ttl(Instant expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt).plus(replayRetention);
        return remaining.isNegative() || remaining.isZero() ? replayRetention : remaining;
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Refresh Session 缺少字段：" + key);
        }
        return value.toString();
    }

    private String tokenKey(String tokenHash) {
        return TOKEN_PREFIX + tokenHash;
    }

    private String familyKey(String familyId) {
        return FAMILY_PREFIX + familyId;
    }
}
