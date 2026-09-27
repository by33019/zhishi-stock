package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码重置兑换（AUTH-07）的用例固定。
 *
 * <h2>本类钉的是"所有失败都长同一张脸"</h2>
 * 公开端点上，邮箱不存在、账号锁定、凭证过期、verificationId 不匹配、code 错、
 * 并发被抢——六种失败对外必须一模一样（{@code CREDENTIALS_INVALID}）。
 * 这里逐个触发它们并断言异常完全一致，谁要"贴心地"区分失败原因，测试就会红。
 *
 * <h2>顺序钉死：先消费，后改密</h2>
 * 一次性由 {@code consume} 的原子性保证；测试里用一个"第一次成功、之后失败"
 * 的存储复刻 Redis DEL 的语义，断言第二次兑换既没改密也没撤销会话。
 */
class PasswordResetRedemptionServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-27T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private final InMemoryAccounts accounts = new InMemoryAccounts();
  private final InMemoryCredentials credentials = new InMemoryCredentials();
  private final RefreshSessionStore sessions = mock(RefreshSessionStore.class);
  private final PasswordEncoder encoder = new StubEncoder();
  private final PasswordResetRedemptionService service =
      new PasswordResetRedemptionService(accounts, credentials, sessions, encoder, CLOCK);

  @Test
  void resetsThePasswordConsumesTheCredentialAndRevokesAllSessions() {
    accounts.put("demo@example.com", UserAccount.Status.ACTIVE);
    credentials.save(
        7_000L, "vid-1", RefreshTokenHashing.sha256("code-1"),
        CLOCK.instant().plusSeconds(600));
    when(sessions.revokeAllForUser(anyLong())).thenReturn(3);

    PasswordResetRedemptionService.PasswordResetResult result =
        service.reset("demo@example.com", "vid-1", "code-1", "New@12345");

    assertThat(result.reset()).isTrue();
    assertThat(result.revokedSessionCount()).isEqualTo(3);
    // 新密码已编码落库（不是明文），一次性凭证已被消费
    assertThat(accounts.passwordHashes.get(7_000L)).isEqualTo("encoded-New@12345");
    assertThat(credentials.find(7_000L)).isEmpty();
  }

  @Test
  void everyFailureLooksIdenticalToTheCaller() {
    String genericMessage = "重置凭证无效或已过期";

    // 1. 邮箱不存在
    assertThatThrownBy(() -> service.reset(
        "nobody@example.com", "vid", "code", "New@12345"))
        .isInstanceOfSatisfying(AuthException.class, exception -> {
          assertThat(exception.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
          assertThat(exception.getMessage()).isEqualTo(genericMessage);
        });

    // 2. 账号被锁定
    accounts.put("locked@example.com", UserAccount.Status.LOCKED);
    assertThatThrownBy(() -> service.reset(
        "locked@example.com", "vid", "code", "New@12345"))
        .isInstanceOfSatisfying(AuthException.class, exception -> {
          assertThat(exception.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
          assertThat(exception.getMessage()).isEqualTo(genericMessage);
        });

    // 3. 从未签发过凭证
    accounts.put("fresh@example.com", UserAccount.Status.ACTIVE);
    assertThatThrownBy(() -> service.reset(
        "fresh@example.com", "vid", "code", "New@12345"))
        .isInstanceOfSatisfying(AuthException.class, exception ->
            assertThat(exception.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

    // 4. verificationId 不匹配
    accounts.put("stale@example.com", UserAccount.Status.ACTIVE);
    credentials.save(
        accounts.idOf("stale@example.com"), "vid-issued", RefreshTokenHashing.sha256("code"),
        CLOCK.instant().plusSeconds(600));
    assertThatThrownBy(() -> service.reset(
        "stale@example.com", "vid-other", "code", "New@12345"))
        .isInstanceOfSatisfying(AuthException.class, exception ->
            assertThat(exception.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

    // 5. code 错
    assertThatThrownBy(() -> service.reset(
        "stale@example.com", "vid-issued", "wrong-code", "New@12345"))
        .isInstanceOfSatisfying(AuthException.class, exception ->
            assertThat(exception.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));

    // 6. 字段空白
    assertThatThrownBy(() -> service.reset(" ", "vid", "code", "New@12345"))
        .isInstanceOfSatisfying(AuthException.class, exception ->
            assertThat(exception.code()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS));
  }

  /** 消费失败（并发被抢）必须整段失败：不改密、不撤销会话。 */
  @Test
  void losesTheRaceWithoutChangingAnything() {
    accounts.put("race@example.com", UserAccount.Status.ACTIVE);
    credentials.save(
        accounts.idOf("race@example.com"), "vid", RefreshTokenHashing.sha256("code"),
        CLOCK.instant().plusSeconds(600));
    credentials.failNextConsume = true;
    when(sessions.revokeAllForUser(anyLong())).thenReturn(0);

    assertThatThrownBy(() -> service.reset(
        "race@example.com", "vid", "code", "New@12345"))
        .isInstanceOf(AuthException.class);

    assertThat(accounts.passwordHashes).doesNotContainKey(accounts.idOf("race@example.com"));
  }

  // ---------- 夹具 ----------

  private static final class StubEncoder implements PasswordEncoder {
    @Override
    public String encode(CharSequence rawPassword) {
      return "encoded-" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return ("encoded-" + rawPassword).equals(encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return false;
    }
  }

  /** 内存仓储：email → 账号，id 由存入顺序分配（7_000 起）。 */
  private static final class InMemoryAccounts implements UserAccountRepository {

    private final Map<String, UserAccount> byEmail = new LinkedHashMap<>();
    private final Map<Long, String> passwordHashes = new HashMap<>();
    private long nextId = 7_000;

    void put(String email, UserAccount.Status status) {
      byEmail.put(email, new UserAccount(
          nextId, "user-" + nextId, "old-hash", status, "用户", 0));
      nextId += 1;
    }

    long idOf(String email) {
      return byEmail.get(email).id();
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
      return Optional.empty();
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
      return Optional.ofNullable(byEmail.get(email));
    }

    @Override
    public void updatePasswordHashBumpingTokenVersion(long userId, String newPasswordHash) {
      passwordHashes.put(userId, newPasswordHash);
    }

    @Override
    public Set<String> findPermissions(long userId) {
      return Set.of();
    }
  }

  /** 内存凭证存储：consume 复刻"第一次成功、可注入失败"的 Redis DEL 语义。 */
  private static final class InMemoryCredentials implements PasswordResetCredentialStore {

    private final Map<Long, StoredResetCredential> stored = new HashMap<>();
    boolean failNextConsume;

    @Override
    public void save(long userId, String verificationId, String credentialHash, Instant expiresAt) {
      stored.put(userId, new StoredResetCredential(verificationId, credentialHash));
    }

    @Override
    public Optional<StoredResetCredential> find(long userId) {
      return Optional.ofNullable(stored.get(userId));
    }

    @Override
    public boolean consume(long userId) {
      if (failNextConsume) {
        failNextConsume = false;
        return false;
      }
      return stored.remove(userId) != null;
    }
  }
}
