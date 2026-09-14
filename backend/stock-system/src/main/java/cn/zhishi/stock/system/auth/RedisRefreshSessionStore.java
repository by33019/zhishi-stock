package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisRefreshSessionStore implements RefreshSessionStore {

    private static final String TOKEN_PREFIX = "auth:refresh:token:";
    private static final String FAMILY_PREFIX = "auth:refresh:family:";
    private static final String FAMILY_REVOKED_PREFIX = "auth:refresh:revoked-family:";
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return 0 end
            if status == 'ROTATED' then return 2 end
            if status ~= 'ACTIVE' then return 0 end
            if redis.call('HGET', KEYS[1], 'familyId') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'status', 'ROTATED')
            redis.call('HSET', KEYS[2],
              'familyId', ARGV[1],
              'userId', ARGV[2],
              'expiresAt', ARGV[3],
              'status', 'ACTIVE')
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            redis.call('SADD', KEYS[3], ARGV[5])
            redis.call('PEXPIRE', KEYS[3], ARGV[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REVOKE_FAMILY_SCRIPT =
            new DefaultRedisScript<>("""
                    local members = redis.call('SMEMBERS', KEYS[1])
                    for _, tokenHash in ipairs(members) do
                      local tokenKey = ARGV[1] .. tokenHash
                      if redis.call('EXISTS', tokenKey) == 1 then
                        redis.call('HSET', tokenKey, 'status', 'REVOKED')
                      end
                    end
                    local familyTtl = redis.call('PTTL', KEYS[1])
                    local markerTtl = tonumber(ARGV[2])
                    if familyTtl > markerTtl then markerTtl = familyTtl end
                    redis.call('PSETEX', KEYS[2], markerTtl, '1')
                    return #members
                    """, Long.class);

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
    public RotationOutcome rotate(RefreshTokenRecord previous, RefreshTokenRecord next) {
        Long result = redis.execute(
                ROTATE_SCRIPT,
                List.of(
                        tokenKey(previous.tokenHash()),
                        tokenKey(next.tokenHash()),
                        familyKey(previous.familyId()),
                        revokedFamilyKey(previous.familyId())),
                previous.familyId(),
                Long.toString(next.userId()),
                Long.toString(next.expiresAt().toEpochMilli()),
                Long.toString(ttl(next.expiresAt()).toMillis()),
                next.tokenHash());
        if (Long.valueOf(1L).equals(result)) {
            return RotationOutcome.SUCCESS;
        }
        if (Long.valueOf(2L).equals(result)) {
            return RotationOutcome.REUSED;
        }
        return RotationOutcome.INVALID;
    }

    @Override
    public void revokeFamily(String familyId) {
        redis.execute(
                REVOKE_FAMILY_SCRIPT,
                List.of(familyKey(familyId), revokedFamilyKey(familyId)),
                TOKEN_PREFIX,
                Long.toString(replayRetention.toMillis()));
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

    private String revokedFamilyKey(String familyId) {
        return FAMILY_REVOKED_PREFIX + familyId;
    }
}
