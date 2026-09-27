package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.AuthenticationService;
import cn.zhishi.stock.system.auth.LoginTokens;
import cn.zhishi.stock.system.auth.PasswordResetRedemptionService;
import cn.zhishi.stock.system.auth.RefreshResult;
import cn.zhishi.stock.system.auth.RefreshSessionService;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import cn.zhishi.stock.system.ratelimit.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private static final String RESET_SCOPE = "auth:password-reset";

    private final AuthenticationService authentication;
    private final RefreshSessionService sessions;
    private final AccessTokenBlacklist blacklist;
    private final PasswordResetRedemptionService passwordReset;
    private final IdempotencyGuard idempotency;
    private final RequestRateLimiter rateLimiter;
    private final Clock clock;
    private final boolean secureCookie;

    public AuthController(
            AuthenticationService authentication,
            RefreshSessionService sessions,
            AccessTokenBlacklist blacklist,
            PasswordResetRedemptionService passwordReset,
            IdempotencyGuard idempotency,
            RequestRateLimiter rateLimiter,
            Clock clock,
            @Value("${stock.auth.cookie-secure:true}") boolean secureCookie) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.blacklist = blacklist;
        this.passwordReset = passwordReset;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.secureCookie = secureCookie;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
    }

    /**
     * AUTH-07：兑换一次性凭证并重置密码（PUBLIC）。
     *
     * <p>所有失败（邮箱不存在 / 凭证过期 / 校验不匹配 / 账号锁定）都是同一句
     * {@code CREDENTIALS_INVALID}——公开端点不做任何区分，见
     * {@code PasswordResetRedemptionService} 的说明。
     *
     * <p>幂等键按匿名处理（{@code userId=0}）：指纹里含完整请求体，同键同体重放
     * 回放第一次结果，同键异体 409。重置成功后旧会话全部作废，攻击者拿旧会话
     * 重放也只改一次密码。
     */
    @PostMapping("/password/reset")
    public ApiResponse<PasswordResetResponse> resetPassword(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ResetPasswordRequest body,
            HttpServletRequest request) {
        PasswordResetRedemptionService.PasswordResetResult result = idempotency.execute(
                RESET_SCOPE,
                0L,
                IdempotencyGuard.requireKey(idempotencyKey),
                body,
                PasswordResetRedemptionService.PasswordResetResult.class,
                () -> passwordReset.reset(
                        body.email(), body.verificationId(), body.verificationCode(),
                        body.newPassword()));
        return ApiResponse.success(
                new PasswordResetResponse(result.reset(), result.revokedSessionCount()),
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request) {
        // §22.1 登录基线：10 次/15 分钟，维度为账号+IP。失败锁定由
        // LoginAttemptStore 负责（失败后逐步收紧），这里限的是"尝试"本身。
        rateLimiter.acquire(
                "login:" + body.account().trim() + ":" + clientIp(request),
                10, Duration.ofMinutes(15));
        LoginTokens tokens = authentication.login(body.account(), body.password());
        UserAccount user = tokens.user();
        UserSummary summary = user == null
                ? new UserSummary(null, body.account(), body.account())
                : new UserSummary(Long.toString(user.id()), user.username(), user.displayName());
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
                        Long.toString(principal.userId()),
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

    public record UserSummary(String userId, String username, String displayName) {
    }

    public record LogoutResponse(boolean loggedOut, int revokedSessionCount) {
    }

    public record SessionStatusResponse(
            boolean authenticated,
            String userId,
            String tokenExpiresAt,
            boolean tokenVersionValid) {
    }

    /** AUTH-07 的请求体。 */
    public record ResetPasswordRequest(
            @NotBlank String email,
            @NotBlank String verificationId,
            @NotBlank String verificationCode,
            @NotBlank String newPassword) {
    }

    /** AUTH-07 的响应体。 */
    public record PasswordResetResponse(boolean reset, int revokedSessionCount) {
    }
}
