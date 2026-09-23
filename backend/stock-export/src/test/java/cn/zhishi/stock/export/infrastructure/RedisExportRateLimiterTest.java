package cn.zhishi.stock.export.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.export.application.ExportErrorCode;
import cn.zhishi.stock.export.application.ExportException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 导出限流的 Redis 实现（契约 §22.1：榜单导出 2 次/分钟，维度为用户）。
 *
 * <p>不连真 Redis：要钉的是"计数怎么算、窗口边界落在哪个键上、Redis 抖动时放行还是拦下"，
 * 这些都与 Redis 本身无关。
 */
class RedisExportRateLimiterTest {

  private static final long USER_ID = 42L;
  private static final int LIMIT = 2;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  /** 2026-09-22 15:55:00 +08:00 → epoch 秒落在第 1789883700/60 个分钟桶里。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-22T07:55:00Z"), ZoneId.of("Asia/Shanghai"));

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  @BeforeEach
  void wireRedis() {
    when(redis.opsForValue()).thenReturn(values);
  }

  @Test
  void allowsRequestsUpToTheLimit() {
    RedisExportRateLimiter limiter = limiter();
    when(values.increment(any())).thenReturn(1L, 2L);

    assertThatCode(() -> limiter.acquire(USER_ID)).doesNotThrowAnyException();
    assertThatCode(() -> limiter.acquire(USER_ID)).doesNotThrowAnyException();
  }

  @Test
  void rejectsTheRequestThatExceedsTheLimitWithRateLimited() {
    RedisExportRateLimiter limiter = limiter();
    when(values.increment(any())).thenReturn(3L);

    assertThatThrownBy(() -> limiter.acquire(USER_ID))
        .isInstanceOf(ExportException.class)
        .extracting(exception -> ((ExportException) exception).code())
        .isEqualTo(ExportErrorCode.RATE_LIMITED);
  }

  /**
   * 桶名带分钟号，窗口边界由**键名**决定而不是 TTL。于是每次 {@code INCR} 顺手
   * {@code EXPIRE} 是安全的：它不可能把窗口往后推。
   */
  @Test
  void countsIntoAKeyScopedByUserAndMinuteBucket() {
    RedisExportRateLimiter limiter = limiter();
    when(values.increment(any())).thenReturn(1L);

    limiter.acquire(USER_ID);

    long bucket = CLOCK.instant().getEpochSecond() / WINDOW.toSeconds();
    String expected = "export:rate:" + USER_ID + ":" + bucket;
    verify(values).increment(expected);
    verify(redis).expire(eq(expected), eq(WINDOW));
  }

  /** 不同用户不能共用一个桶，否则"A 刷满了"会连带把 B 挡在门外。 */
  @Test
  void keepsDifferentUsersInDifferentBuckets() {
    RedisExportRateLimiter limiter = limiter();
    when(values.increment(any())).thenReturn(1L);

    limiter.acquire(1L);
    limiter.acquire(2L);

    verify(values).increment("export:rate:1:" + bucket());
    verify(values).increment("export:rate:2:" + bucket());
  }

  /**
   * {@code INCR} 返回 null 说明连接层出了问题。此时**放行**比拦下更安全：
   * 限流是保护措施，不是业务事实——它不该成为"导出整个不可用"的原因。
   */
  @Test
  void allowsTheRequestWhenTheCounterComesBackNull() {
    RedisExportRateLimiter limiter = limiter();
    when(values.increment(any())).thenReturn(null);

    assertThatCode(() -> limiter.acquire(USER_ID)).doesNotThrowAnyException();
    verify(redis, never()).expire(any(), any());
  }

  @Test
  void usesTheConfiguredLimitAndWindow() {
    RedisExportRateLimiter limiter = new RedisExportRateLimiter(redis, 1, Duration.ofSeconds(30), CLOCK);
    when(values.increment(any())).thenReturn(2L);

    assertThatThrownBy(() -> limiter.acquire(USER_ID)).isInstanceOf(ExportException.class);
    verify(redis).expire(any(), eq(Duration.ofSeconds(30)));
  }

  private RedisExportRateLimiter limiter() {
    return new RedisExportRateLimiter(redis, LIMIT, WINDOW, CLOCK);
  }

  private static long bucket() {
    return Math.floorDiv(CLOCK.instant().getEpochSecond(), WINDOW.toSeconds());
  }
}
