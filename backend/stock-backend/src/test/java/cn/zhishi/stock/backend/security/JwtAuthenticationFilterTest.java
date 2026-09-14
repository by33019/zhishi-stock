package cn.zhishi.stock.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.JwtAccessTokenService;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T01:30:00Z"), ZoneOffset.UTC);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatesValidNonRevokedBearerToken() throws Exception {
    var tokens = new JwtAccessTokenService(
        "0123456789abcdef0123456789abcdef", CLOCK, Duration.ofMinutes(15));
    var user = new UserAccount(1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户");
    var accessToken = tokens.issue(user, Set.of("watchlist:read"));
    AccessTokenBlacklist blacklist = new AccessTokenBlacklist() {
      @Override
      public boolean contains(String jti) {
        return false;
      }

      @Override
      public void add(String jti, Instant expiresAt) {
      }
    };
    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.findById(user.id())).thenReturn(Optional.of(user));
    var filter = new JwtAuthenticationFilter(tokens, blacklist, accounts);
    var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    request.addHeader("Authorization", "Bearer " + accessToken.value());

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getName()).isEqualTo("demo");
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("watchlist:read");
  }

  @Test
  void rejectsTokenWhenAccountTokenVersionHasChanged() throws Exception {
    var tokens = new JwtAccessTokenService(
        "0123456789abcdef0123456789abcdef", CLOCK, Duration.ofMinutes(15));
    var issuedUser = new UserAccount(
        1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户", 3);
    var currentUser = new UserAccount(
        1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户", 4);
    var accessToken = tokens.issue(issuedUser, Set.of("watchlist:read"));
    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.findById(issuedUser.id())).thenReturn(Optional.of(currentUser));
    var filter = new JwtAuthenticationFilter(tokens, mock(AccessTokenBlacklist.class), accounts);
    var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
    request.addHeader("Authorization", "Bearer " + accessToken.value());

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
