package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 刷新会话存储里**与 Redis 本身无关的那部分事实**：键名、反向索引、计数口径。
 *
 * <p>不连真 Redis：要钉的是"强制下线凭什么能找到这个用户的全部会话"、
 * "{@code revokedSessionCount} 数的是哪一类东西"，这些由键名与返回值决定。
 * Lua 脚本的原子性由 Redis 保证，不在这里重测。
 */
class RedisRefreshSessionStoreTest {

  private static final long USER_ID = 1001L;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
  private static final Duration RETENTION = Duration.ofDays(1);

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

  @SuppressWarnings("unchecked")
  private final SetOperations<String, String> sets = mock(SetOperations.class);

  @BeforeEach
  void wireRedis() {
    when(redis.opsForHash()).thenReturn(hashes);
    when(redis.opsForSet()).thenReturn(sets);
  }

  /**
   * 保存时除了令牌自身的字段，还必须把会话族记到 {@code auth:refresh:user:{userId}} 下：
   * 强制下线只有这一条路能找到"这个用户有哪些会话"。
   */
  @Test
  void recordsTheFamilyUnderTheUserIndexWhenSaving() {
    store().save(new RefreshTokenRecord(
        "hash-1", "family-1", USER_ID, 5, CLOCK.instant().plus(Duration.ofDays(7)),
        RefreshTokenRecord.Status.ACTIVE));

    verify(sets).add("auth:refresh:family:family-1", "hash-1");
    verify(sets).add("auth:refresh:user:" + USER_ID, "family-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<Object, Object>> fields = ArgumentCaptor.forClass(Map.class);
    verify(hashes).putAll(eq("auth:refresh:token:hash-1"), fields.capture());
    assertThat(fields.getValue())
        .containsEntry("tokenVersion", "5")
        .containsEntry("familyId", "family-1")
        .containsEntry("status", "ACTIVE");
  }

  /** 计数 = 本次实际被翻转的令牌条数，把每个会话族的结果相加。 */
  @Test
  void sumsNewlyRevokedTokensAcrossEveryFamilyOfTheUser() {
    when(sets.members("auth:refresh:user:" + USER_ID))
        .thenReturn(Set.of("family-1", "family-2"));
    // 两个会话族的脚本返回值：2 + 1。
    when(redis.<Long>execute(any(), anyList(), any(), any())).thenReturn(2L, 1L);

    assertThat(store().revokeAllForUser(USER_ID)).isEqualTo(3);
  }

  /** 没有会话时返回 0，而不是因为 {@code members} 给 null 而抛异常。 */
  @Test
  void returnsZeroWhenTheUserHasNoSessions() {
    when(sets.members("auth:refresh:user:" + USER_ID)).thenReturn(Set.of());

    assertThat(store().revokeAllForUser(USER_ID)).isZero();
  }

  /** 升级前签发的会话里没有版本字段：按 0 处理，而不是让刷新接口报 500。 */
  @Test
  void treatsAMissingTokenVersionAsZero() {
    Map<Object, Object> legacy = new HashMap<>();
    legacy.put("familyId", "family-1");
    legacy.put("userId", Long.toString(USER_ID));
    legacy.put("expiresAt", Long.toString(CLOCK.instant().plusSeconds(3600).toEpochMilli()));
    legacy.put("status", RefreshTokenRecord.Status.ACTIVE.name());
    when(hashes.entries("auth:refresh:token:hash-1")).thenReturn(legacy);

    Optional<RefreshTokenRecord> found = store().findByTokenHash("hash-1");

    assertThat(found).isPresent();
    assertThat(found.orElseThrow().tokenVersion()).isZero();
  }

  /** 脚本返回 3 表示令牌里的版本已落后：映射为 {@code STALE_VERSION}，由服务层给出明确文案。 */
  @Test
  void mapsTheStaleVersionReturnCode() {
    when(redis.<Long>execute(any(), anyList(), any(), any(), any(), any(), any(), any()))
        .thenReturn(3L);
    RefreshTokenRecord previous = new RefreshTokenRecord(
        "hash-1", "family-1", USER_ID, 0, CLOCK.instant().plus(Duration.ofDays(7)),
        RefreshTokenRecord.Status.ACTIVE);
    RefreshTokenRecord next = new RefreshTokenRecord(
        "hash-2", "family-1", USER_ID, 1, CLOCK.instant().plus(Duration.ofDays(7)),
        RefreshTokenRecord.Status.ACTIVE);

    assertThat(store().rotate(previous, next))
        .isEqualTo(RefreshSessionStore.RotationOutcome.STALE_VERSION);
  }

  private RedisRefreshSessionStore store() {
    return new RedisRefreshSessionStore(redis, CLOCK, RETENTION);
  }
}
