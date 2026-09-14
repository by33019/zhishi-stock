package cn.zhishi.stock.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JwtAccessTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T01:30:00Z");
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void signsAndVerifiesAccessTokenClaims() {
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var service = new JwtAccessTokenService(SECRET, clock, Duration.ofMinutes(15));
    var user = new UserAccount(
        1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户", 7);

    var token = service.issue(user, Set.of("market:read", "watchlist:read"));
    var principal = service.verify(token.value());

    assertThat(token.expiresInSeconds()).isEqualTo(900);
    assertThat(principal.userId()).isEqualTo(1001L);
    assertThat(principal.username()).isEqualTo("demo");
    assertThat(principal.permissions()).containsExactlyInAnyOrder("market:read", "watchlist:read");
    assertThat(principal.jti()).isEqualTo(token.jti());
    assertThat(principal.expiresAt()).isEqualTo(NOW.plusSeconds(900));
    assertThat(principal.tokenVersion()).isEqualTo(7);
  }

  @Test
  void rejectsTokenSignedWithAnotherSecret() {
    var clock = Clock.fixed(NOW, ZoneOffset.UTC);
    var issuer = new JwtAccessTokenService(SECRET, clock, Duration.ofMinutes(15));
    var verifier = new JwtAccessTokenService(
        "abcdef0123456789abcdef0123456789", clock, Duration.ofMinutes(15));
    var user = new UserAccount(1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户");

    var token = issuer.issue(user, Set.of("market:read"));

    assertThatThrownBy(() -> verifier.verify(token.value()))
        .isInstanceOf(InvalidAccessTokenException.class);
  }
}
