package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthenticationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T01:30:00Z");

  private final Map<Long, LoginAttemptState> attempts = new HashMap<>();
  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private UserAccount account;
  private AuthenticationService service;

  @BeforeEach
  void setUp() {
    account = new UserAccount(
        1001L,
        "demo",
        passwordEncoder.encode("Stock@123"),
        UserAccount.Status.ACTIVE,
        "演示用户");
    UserAccountRepository accounts = new UserAccountRepository() {
      @Override
      public Optional<UserAccount> findByUsername(String username) {
        return "demo".equals(username) ? Optional.of(account) : Optional.empty();
      }

      @Override
      public Set<String> findPermissions(long userId) {
        return Set.of("watchlist:read", "market:read");
      }
    };
    LoginAttemptStore attemptStore = new LoginAttemptStore() {
      @Override
      public Optional<LoginAttemptState> find(long userId) {
        return Optional.ofNullable(attempts.get(userId));
      }

      @Override
      public void save(long userId, LoginAttemptState state) {
        attempts.put(userId, state);
      }

      @Override
      public boolean recordFailure(
          long userId, Instant now, int maximumFailures, Duration lockDuration) {
        LoginAttemptState current = attempts.getOrDefault(userId, new LoginAttemptState(0, null));
        if (current.isLocked(now)) return true;
        int failures = current.lockedUntil() == null ? current.failures() + 1 : 1;
        Instant lockedUntil = failures >= maximumFailures ? now.plus(lockDuration) : null;
        attempts.put(userId, new LoginAttemptState(failures, lockedUntil));
        return lockedUntil != null;
      }

      @Override
      public void clear(long userId) {
        attempts.remove(userId);
      }
    };
    TokenIssuer tokenIssuer = (user, permissions) ->
        new LoginTokens("access-token", "refresh-token", 900, permissions);
    service = new AuthenticationService(
        accounts,
        attemptStore,
        passwordEncoder,
        tokenIssuer,
        Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")),
        5,
        Duration.ofMinutes(15));
  }

  @Test
  void successfulLoginClearsFailuresAndIssuesPermissions() {
    attempts.put(account.id(), new LoginAttemptState(2, null));

    var result = service.login("demo", "Stock@123");

    assertThat(result.accessToken()).isEqualTo("access-token");
    assertThat(result.permissions()).containsExactlyInAnyOrder("watchlist:read", "market:read");
    assertThat(attempts).doesNotContainKey(account.id());
  }

  @Test
  void fifthConsecutiveFailureLocksAccountForFifteenMinutes() {
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertThatThrownBy(() -> service.login("demo", "wrong-password"))
          .isInstanceOf(AuthException.class)
          .extracting(error -> ((AuthException) error).code())
          .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    assertThatThrownBy(() -> service.login("demo", "wrong-password"))
        .isInstanceOf(AuthException.class)
        .extracting(error -> ((AuthException) error).code())
        .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED);
    assertThat(attempts.get(account.id()).lockedUntil())
        .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
  }

  @Test
  void disabledAccountIsRejectedBeforePasswordVerification() {
    account = new UserAccount(
        account.id(), account.username(), account.passwordHash(), UserAccount.Status.DISABLED, "演示用户");

    assertThatThrownBy(() -> service.login("demo", "Stock@123"))
        .isInstanceOf(AuthException.class)
        .extracting(error -> ((AuthException) error).code())
        .isEqualTo(AuthErrorCode.ACCOUNT_DISABLED);
  }

  @Test
  void unknownAccountStillPerformsPasswordHashCheck() {
    UserAccountRepository missingAccounts = new UserAccountRepository() {
      @Override
      public Optional<UserAccount> findByUsername(String username) {
        return Optional.empty();
      }

      @Override
      public Set<String> findPermissions(long userId) {
        return Set.of();
      }
    };
    PasswordEncoder encoder = mock(PasswordEncoder.class);
    when(encoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("dummy-hash");
    var authentication = new AuthenticationService(
        missingAccounts,
        mock(LoginAttemptStore.class),
        encoder,
        mock(TokenIssuer.class),
        Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")),
        5,
        Duration.ofMinutes(15));

    assertThatThrownBy(() -> authentication.login("missing", "candidate"))
        .isInstanceOf(AuthException.class);
    verify(encoder).matches("candidate", "dummy-hash");
  }
}
