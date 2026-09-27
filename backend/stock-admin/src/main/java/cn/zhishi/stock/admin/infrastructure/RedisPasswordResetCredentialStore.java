package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.system.auth.PasswordResetCredentialStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link PasswordResetCredentialStore} 的 Redis 实现（契约 §16.1 ADM-USR-07 + §5 AUTH-07）。
 *
 * <h2>只存哈希、只存一个人</h2>
 * 存的是 {@code sha256(credential)}，不是明文——与刷新令牌同一处置（见
 * {@code RefreshTokenHashing}）。同一用户重复发起重置会**覆盖**上一条：
 * 留着两条有效凭证意味着存在两个都能改密的入口，而审计上只会看到"发了两次"。
 *
 * <h2>TTL 取到过期时刻而不是固定时长</h2>
 * 用例层算好的 {@code expiresAt} 是"凭证何时失效"的唯一答案。这里若用固定时长，
 * 重试或时钟偏差会让实际有效期与响应里的 {@code expiresInSeconds} 对不上。
 *
 * <h2>值是 {@code verificationId:credentialHash}，冒号作分隔</h2>
 * 两个值都不含冒号（verificationId 是 UUID、哈希是十六进制），分隔符安全；
 * 不用 JSON 是为了让"存在性"与"内容比对"在纯字符串上就能完成。
 * {@link #consume} 直接 DEL：Redis 删除的返回值就是一次性的原子保证。
 */
public class RedisPasswordResetCredentialStore implements PasswordResetCredentialStore {

    private static final String KEY_PREFIX = "auth:admin:password-reset:";
    private static final String SEPARATOR = ":";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisPasswordResetCredentialStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void save(long userId, String verificationId, String credentialHash, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // 已经过期的凭证没有写入的意义；写入还会得到一个负 TTL 而立刻消失，
            // 让"写成功但读不到"变成一件需要解释的事。
            throw new IllegalArgumentException("凭证过期时刻早于当前时间：" + expiresAt);
        }
        redis.opsForValue().set(key(userId), verificationId + SEPARATOR + credentialHash, ttl);
    }

    @Override
    public Optional<StoredResetCredential> find(long userId) {
        String value = redis.opsForValue().get(key(userId));
        if (value == null) {
            return Optional.empty();
        }
        int separatorAt = value.indexOf(SEPARATOR);
        if (separatorAt <= 0) {
            // 值格式被外力破坏：当作无凭证处理，而不是把半个秘密当有效数据。
            return Optional.empty();
        }
        return Optional.of(new StoredResetCredential(
                value.substring(0, separatorAt), value.substring(separatorAt + 1)));
    }

    @Override
    public boolean consume(long userId) {
        return Boolean.TRUE.equals(redis.delete(key(userId)));
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
