package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.UserAccount;
import cn.zhishi.stock.system.auth.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class CurrentUserController {

    private final UserAccountRepository accounts;
    private final Clock clock;

    public CurrentUserController(UserAccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @GetMapping
    public ApiResponse<UserProfile> me(
            Authentication authentication,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = principal(authentication);
        UserAccount account = accounts.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException("当前用户不存在"));
        return success(
                new UserProfile(
                        Long.toString(account.id()),
                        account.username(),
                        account.displayName(),
                        account.status().name()),
                request);
    }

    @GetMapping("/permissions")
    public ApiResponse<PermissionSummary> permissions(
            Authentication authentication,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = principal(authentication);
        UserAccount account = accounts.findById(principal.userId())
                .orElseThrow(() -> new IllegalStateException("当前用户不存在"));
        return success(
                new PermissionSummary(
                        List.of(),
                        accounts.findPermissions(principal.userId()),
                        List.of(),
                        account.tokenVersion()),
                request);
    }

    private AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data,
                TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    public record UserProfile(
            String userId,
            String username,
            String displayName,
            String status) {
    }

    public record PermissionSummary(
            List<String> roles,
            Set<String> permissionCodes,
            List<String> menus,
            int tokenVersion) {
    }
}
