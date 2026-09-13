package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.LoginTokens;
import cn.zhishi.stock.system.auth.RefreshResult;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final AuthenticationService authentication;
    private final RefreshSessionService sessions;
    private final AccessTokenBlacklist blacklist;
    private final Clock clock;
    private final boolean secureCookie;

    public AuthController(
            AuthenticationService authentication,
            RefreshSessionService sessions,
            AccessTokenBlacklist blacklist,
            Clock clock,
            @Value("${stock.auth.cookie-secure:true}") boolean secureCookie) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.blacklist = blacklist;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        LoginTokens tokens = authentication.login(body.account(), body.password());
        UserAccount user = tokens.user();
        UserSummary summary = user == null
                ? new UserSummary(null, body.account(), body.account())
                : new UserSummary(user.id(), user.username(), user.displayName());
        TokenResponse data = new TokenResponse(
                tokens.accessToken(),
                tokens.expiresInSeconds(),
                REFRESH_TTL.toSeconds(),
                summary,
                tokens.permissions());
        return tokenResponse(data, tokens.refreshToken(), request);
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(REFRESH_COOKIE) String refreshToken,
            HttpServletRequest request) {
        RefreshResult result = sessions.rotate(refreshToken);
        TokenResponse data = new TokenResponse(
                result.accessToken(),
                result.expiresInSeconds(),
                REFRESH_TTL.toSeconds(),
                null,
                result.permissions());
        return tokenResponse(data, result.refreshToken(), request);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken,
            Authentication security,
            HttpServletRequest request) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessions.revoke(refreshToken);
        }
        AccessTokenPrincipal principal = (AccessTokenPrincipal) security.getPrincipal();
        blacklist.add(principal.jti(), principal.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .body(ApiResponse.success(
                        new LogoutResponse(true, refreshToken == null ? 0 : 1),
                        TraceIdFilter.current(request),
                        OffsetDateTime.now(clock)));
    }

    @GetMapping("/session-status")
    public ApiResponse<SessionStatusResponse> sessionStatus(
            Authentication security,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = (AccessTokenPrincipal) security.getPrincipal();
        return ApiResponse.success(
                new SessionStatusResponse(
                        true,
                        principal.userId(),
                        principal.expiresAt().toString(),
                        true),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    private ResponseEntity<ApiResponse<TokenResponse>> tokenResponse(
            TokenResponse data,
            String refreshToken,
            HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refreshToken, REFRESH_TTL).toString())
                .body(ApiResponse.success(
                        data,
                        TraceIdFilter.current(request),
                        OffsetDateTime.now(clock)));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }

    public record LoginRequest(
            @NotBlank(message = "账号不能为空") String account,
            @NotBlank(message = "密码不能为空") String password,
            String deviceName) {
    }

    public record TokenResponse(
            String accessToken,
            long accessExpiresInSeconds,
            long refreshExpiresInSeconds,
            UserSummary user,
            Set<String> permissions) {
    }

    public record UserSummary(Long userId, String username, String displayName) {
    }

    public record LogoutResponse(boolean loggedOut, int revokedSessionCount) {
    }

    public record SessionStatusResponse(
            boolean authenticated,
            long userId,
            String tokenExpiresAt,
            boolean tokenVersionValid) {
    }
}
