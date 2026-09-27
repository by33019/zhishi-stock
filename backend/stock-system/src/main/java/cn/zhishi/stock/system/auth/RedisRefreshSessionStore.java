package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class RedisRefreshSessionStore implements RefreshSessionStore {

    private static final String TOKEN_PREFIX = "auth:refresh:token:";
    private static final String FAMILY_PREFIX = "auth:refresh:family:";
    private static final String FAMILY_REVOKED_PREFIX = "auth:refresh:revoked-family:";
    /** 用户 → 会话族集合。强制下线（ADM-USR-05/06/08/09）只能靠这张反向索引找到该用户的全部会话。 */
    private static final String USER_PREFIX = "auth:refresh:user:";

    /**
     * 原子轮换。
     *
     * <p>返回码：{@code 1} 成功、{@code 2} 检测到重放、{@code 3} 令牌版本落后于用户当前版本
     * （管理员已强制下线）、{@code 0} 其余无效情形。
     *
     * <p>版本比对放在脚本里而不是只放在 Java 侧：Java 侧"读用户 → 比对 → 轮换"之间
     * 存在窗口，管理员的强制下线若正好落在窗口里，这次刷新就会成功换出一张新版本的
     * access token——撤销被绕过一次。放进同一个 Lua 里，比对与轮换之间没有可插入的时刻。
     */
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
            local status = redis.call('HGET', KEYS[1], 'status')
            if not status then return 0 end
            if status == 'ROTATED' then return 2 end
            if status ~= 'ACTIVE' then return 0 end
            if redis.call('HGET', KEYS[1], 'familyId') ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'tokenVersion') ~= ARGV[6] then return 3 end
            redis.call('HSET', KEYS[1], 'status', 'ROTATED')
            redis.call('HSET', KEYS[2],
              'familyId', ARGV[1],
              'userId', ARGV[2],
              'tokenVersion', ARGV[6],
              'expiresAt', ARGV[3],
              'status', 'ACTIVE')
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            redis.call('SADD', KEYS[3], ARGV[5])
            redis.call('PEXPIRE', KEYS[3], ARGV[4])
            redis.call('SADD', KEYS[5], ARGV[1])
            redis.call('PEXPIRE', KEYS[5], ARGV[4])
            return 1
            """, Long.class);

    /**
     * 撤销一个会话族，返回**本次实际被翻转**的令牌条数。
     *
     * <p>已处于 {@code REVOKED} 的不再计数：{@code revokedSessionCount} 是"这次撤销
     * 撤销掉了几个会话"，把早先就撤销过的再数一遍，会让"重复点两次强制下线"看起来
     * 像撤销了双倍会话。
     */
    private static final DefaultRedisScript<Long> REVOKE_FAMILY_SCRIPT =
            new DefaultRedisScript<>("""
                    local members = redis.call('SMEMBERS', KEYS[1])
                    local revoked = 0
                    for _, tokenHash in ipairs(members) do
                      local tokenKey = ARGV[1] .. tokenHash
                      local status = redis.call('HGET', tokenKey, 'status')
                      if status and status ~= 'REVOKED' then
                        redis.call('HSET', tokenKey, 'status', 'REVOKED')
                        revoked = revoked + 1
                      end
                    end
                    local familyTtl = redis.call('PTTL', KEYS[1])
                    local markerTtl = tonumber(ARGV[2])
                    if familyTtl > markerTtl then markerTtl = familyTtl end
                    redis.call('PSETEX', KEYS[2], markerTtl, '1')
                    return revoked
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
                tokenVersion(values),
                Instant.ofEpochMilli(Long.parseLong(value(values, "expiresAt"))),
                RefreshTokenRecord.Status.valueOf(value(values, "status"))));
    }

    @Override
    public void save(RefreshTokenRecord record) {
        String tokenKey = tokenKey(record.tokenHash());
        Map<String, String> values = new HashMap<>();
        values.put("familyId", record.familyId());
        values.put("userId", Long.toString(record.userId()));
        values.put("tokenVersion", Integer.toString(record.tokenVersion()));
        values.put("expiresAt", Long.toString(record.expiresAt().toEpochMilli()));
        values.put("status", record.status().name());
        redis.<String, String>opsForHash().putAll(tokenKey, values);
        Duration ttl = ttl(record.expiresAt());
        redis.expire(tokenKey, ttl);
        String familyKey = familyKey(record.familyId());
        redis.opsForSet().add(familyKey, record.tokenHash());
        redis.expire(familyKey, ttl);
        String userKey = userKey(record.userId());
        redis.opsForSet().add(userKey, record.familyId());
        redis.expire(userKey, ttl);
    }

    @Override
    public RotationOutcome rotate(RefreshTokenRecord previous, RefreshTokenRecord next) {
        Long result = redis.execute(
                ROTATE_SCRIPT,
                List.of(
                        tokenKey(previous.tokenHash()),
                        tokenKey(next.tokenHash()),
                        familyKey(previous.familyId()),
                        revokedFamilyKey(previous.familyId()),
                        userKey(next.userId())),
                previous.familyId(),
                Long.toString(next.userId()),
                Long.toString(next.expiresAt().toEpochMilli()),
                Long.toString(ttl(next.expiresAt()).toMillis()),
                next.tokenHash(),
                Integer.toString(next.tokenVersion()));
        if (Long.valueOf(1L).equals(result)) {
            return RotationOutcome.SUCCESS;
        }
        if (Long.valueOf(2L).equals(result)) {
            return RotationOutcome.REUSED;
        }
        if (Long.valueOf(3L).equals(result)) {
            return RotationOutcome.STALE_VERSION;
        }
        return RotationOutcome.INVALID;
    }

    @Override
    public void revokeFamily(String familyId) {
        revoke(familyId);
    }

    @Override
    public int revokeAllForUser(long userId) {
        Set<String> families = redis.opsForSet().members(userKey(userId));
        if (families == null || families.isEmpty()) {
            return 0;
        }
        int revoked = 0;
        for (String familyId : families) {
            revoked += revoke(familyId);
        }
        return revoked;
    }

    /** @return 本次实际被翻转的令牌条数 */
    private int revoke(String familyId) {
        Long revoked = redis.execute(
                REVOKE_FAMILY_SCRIPT,
                List.of(familyKey(familyId), revokedFamilyKey(familyId)),
                TOKEN_PREFIX,
                Long.toString(replayRetention.toMillis()));
        return revoked == null ? 0 : revoked.intValue();
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

    /**
     * 版本号缺失时按 0 处理。
     *
     * <p>引入这个字段之前签发的会话里没有它。当成 0 与"当时没有版本概念"的语义一致，
     * 且方向是安全的：用户版本一旦被管理员递增（`1 != 0`），这些老会话照样会被拒。
     * 反过来若当成"无效"，升级瞬间所有已登录用户都会被无理由踢下线。
     */
    private static int tokenVersion(Map<Object, Object> values) {
        Object value = values.get("tokenVersion");
        return value == null ? 0 : Integer.parseInt(value.toString());
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

    private String userKey(long userId) {
        return USER_PREFIX + userId;
    }
}
