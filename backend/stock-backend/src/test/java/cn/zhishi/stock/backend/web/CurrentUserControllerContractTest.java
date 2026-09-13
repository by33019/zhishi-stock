package cn.zhishi.stock.backend.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CurrentUserControllerContractTest {

  @Test
  void returnsCurrentUserAndPermissionsFromAuthenticatedPrincipal() throws Exception {
    UserAccountRepository accounts = mock(UserAccountRepository.class);
    when(accounts.findById(1001L)).thenReturn(Optional.of(new UserAccount(
        1001L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户")));
    when(accounts.findPermissions(1001L)).thenReturn(Set.of("market:read", "watchlist:read"));
    Clock clock = Clock.fixed(
        Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new CurrentUserController(accounts, clock))
        .addFilters(new TraceIdFilter())
        .build();
    var principal = new AccessTokenPrincipal(
        1001L,
        "demo",
        Set.of("market:read", "watchlist:read"),
        "jti-1",
        Instant.parse("2026-09-11T02:15:00Z"));
    var authentication = new UsernamePasswordAuthenticationToken(principal, "token", Set.of());

    mvc.perform(get("/api/v1/users/me").principal(authentication))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(1001))
        .andExpect(jsonPath("$.data.username").value("demo"))
        .andExpect(jsonPath("$.data.displayName").value("演示用户"));

    mvc.perform(get("/api/v1/users/me/permissions").principal(authentication))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissionCodes.length()").value(2));
  }
}
