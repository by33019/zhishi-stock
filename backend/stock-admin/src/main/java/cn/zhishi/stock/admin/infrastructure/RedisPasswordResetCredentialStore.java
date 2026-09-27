package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.PasswordResetCredentialStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 一次性密码重置凭证存 Redis（契约 §16.1 ADM-USR-07）。
 *
 * <h2>只存哈希、只存一个人</h2>
 * 存的是 {@code sha256(credential)}，不是明文——与刷新令牌同一处置（见
 * {@code RefreshTokenHashing}）。同一用户重复发起重置会**覆盖**上一条：
 * 留着两条有效凭证意味着存在两个都能改密的入口，而审计上只会看到"发了两次"。
 *
 * <h2>TTL 取到过期时刻而不是固定时长</h2>
 * 用例层算好的 {@code expiresAt} 是"凭证何时失效"的唯一答案。这里若用固定时长，
 * 重试或时钟偏差会让实际有效期与响应里的 {@code expiresInSeconds} 对不上。
 */
public class RedisPasswordResetCredentialStore implements PasswordResetCredentialStore {

    private static final String KEY_PREFIX = "auth:admin:password-reset:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisPasswordResetCredentialStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void save(long userId, String credentialHash, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // 已经过期的凭证没有写入的意义；写入还会得到一个负 TTL 而立刻消失，
            // 让"写成功但读不到"变成一件需要解释的事。
            throw new IllegalArgumentException("凭证过期时刻早于当前时间：" + expiresAt);
        }
        redis.opsForValue().set(key(userId), credentialHash, ttl);
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
