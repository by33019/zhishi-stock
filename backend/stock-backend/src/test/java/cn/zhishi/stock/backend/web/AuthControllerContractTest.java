package cn.zhishi.stock.backend.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.AuthErrorCode;
import cn.zhishi.stock.system.auth.AuthException;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.LoginTokens;
import cn.zhishi.stock.system.auth.RefreshResult;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerContractTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-11T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private AuthenticationService authentication;
  private RefreshSessionService sessions;
  private AccessTokenBlacklist blacklist;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    authentication = mock(AuthenticationService.class);
    sessions = mock(RefreshSessionService.class);
    blacklist = mock(AccessTokenBlacklist.class);
    mvc = MockMvcBuilders.standaloneSetup(
            new AuthController(authentication, sessions, blacklist, CLOCK, true))
        .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
        .addFilters(new TraceIdFilter())
        .build();
  }

  @Test
  void loginReturnsAccessTokenAndHttpOnlyRefreshCookie() throws Exception {
    var user = new UserAccount(
        9_900_000_000_003L, "demo", "hash", UserAccount.Status.ACTIVE, "演示用户");
    when(authentication.login("demo", "Stock@123"))
        .thenReturn(new LoginTokens(
            "access-token", "refresh-token", 900, Set.of("market:read"), user));

    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"account":"demo","password":"Stock@123","deviceName":"Chrome"}
                """))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("refresh_token=refresh-token"),
            org.hamcrest.Matchers.containsString("HttpOnly"),
            org.hamcrest.Matchers.containsString("Secure"),
            org.hamcrest.Matchers.containsString("SameSite=Strict"))))
        .andExpect(jsonPath("$.data.accessToken").value("access-token"))
        .andExpect(jsonPath("$.data.accessExpiresInSeconds").value(900))
        .andExpect(jsonPath("$.data.user.userId").isString())
        .andExpect(jsonPath("$.data.user.userId").value("9900000000003"))
        .andExpect(jsonPath("$.data.user.username").value("demo"))
        .andExpect(jsonPath("$.data.permissions[0]").value("market:read"));
  }

  @Test
  void refreshRotatesCookieAndReturnsNewAccessToken() throws Exception {
    when(sessions.rotate("refresh-token"))
        .thenReturn(new RefreshResult(
            "access-2", "refresh-2", 900, Set.of("market:read")));

    mvc.perform(post("/api/v1/auth/token/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString(
            "refresh_token=refresh-2")))
        .andExpect(jsonPath("$.data.accessToken").value("access-2"));
  }

  @Test
  void missingRefreshCookieUsesUnifiedErrorEnvelope() throws Exception {
    mvc.perform(post("/api/v1/auth/token/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void malformedLoginJsonUsesUnifiedErrorEnvelope() throws Exception {
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void mapsLockedAccountToStableErrorCode() throws Exception {
    when(authentication.login("demo", "wrong-password"))
        .thenThrow(new AuthException(AuthErrorCode.ACCOUNT_LOCKED, "账户已锁定"));

    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"account":"demo","password":"wrong-password"}
                """))
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
  }

  @Test
  void unexpectedServiceFailureUsesGenericEnvelopeWithoutLeakingDetails() throws Exception {
    when(authentication.login("demo", "Stock@123"))
        .thenThrow(new IllegalStateException("database-password=secret"));

    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"account":"demo","password":"Stock@123"}
                """))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("服务暂时不可用"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void rejectsInvalidLoginFieldsWithFieldErrors() throws Exception {
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"account":"","password":""}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.data.fieldErrors.account").exists())
        .andExpect(jsonPath("$.data.fieldErrors.password").exists());
  }

  @Test
  void logoutRevokesRefreshFamilyAndBlacklistsCurrentJti() throws Exception {
    var expiresAt = Instant.parse("2026-09-11T02:15:00Z");
    var principal = new AccessTokenPrincipal(
        1001L, "demo", Set.of("market:read"), "jti-1", expiresAt);
    var security = new UsernamePasswordAuthenticationToken(principal, "access-token", Set.of());

    mvc.perform(post("/api/v1/auth/logout")
            .principal(security)
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", "refresh-token")))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("refresh_token="),
            org.hamcrest.Matchers.containsString("Max-Age=0"))))
        .andExpect(jsonPath("$.data.loggedOut").value(true));

    verify(sessions).revoke("refresh-token");
    verify(blacklist).add("jti-1", expiresAt);
  }

  @Test
  void sessionStatusReflectsCurrentAccessToken() throws Exception {
    var expiresAt = Instant.parse("2026-09-11T02:15:00Z");
    var principal = new AccessTokenPrincipal(
        1001L, "demo", Set.of("market:read"), "jti-1", expiresAt);
    var security = new UsernamePasswordAuthenticationToken(principal, "access-token", Set.of());

    mvc.perform(get("/api/v1/auth/session-status").principal(security))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.authenticated").value(true))
        .andExpect(jsonPath("$.data.userId").isString())
        .andExpect(jsonPath("$.data.userId").value("1001"))
        .andExpect(jsonPath("$.data.tokenExpiresAt").value("2026-09-11T02:15:00Z"));
  }
}
