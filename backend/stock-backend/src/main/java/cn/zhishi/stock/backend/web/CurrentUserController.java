package cn.zhishi.stock.backend.web;

import cn.zhishi.stock.ai.application.AiQuotaQueryService;
import cn.zhishi.stock.ai.application.AiTaskQuota;
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
    private final AiQuotaQueryService aiQuotas;
    private final Clock clock;

    public CurrentUserController(
            UserAccountRepository accounts,
            AiQuotaQueryService aiQuotas,
            Clock clock) {
        this.accounts = accounts;
        this.aiQuotas = aiQuotas;
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

    /**
     * USER-07：AI 当日配额与并发占用。
     *
     * <h2>为什么它挂在「当前用户」而不是 AI 模块下</h2>
     * 契约把编号与路径都定在这里（{@code /users/me/ai-quota}）。这不是排版问题：
     * 它是**当前用户**的属性，鉴权只需"已登录"，与 AI 模块的
     * {@code ai:read} 之类的权限无关；挪到 {@code /ai/quota} 会让一个没有 AI 权限
     * 的用户连自己还剩几次都用不了。
     *
     * <p>响应形状直接用 {@link AiTaskQuota}——与 AI-03 响应里的 {@code quota} 是同一个
     * 记录的同一批字段。各自定义一个 DTO 就会有两份字段清单，而分叉的表现是
     * "创建页说还剩 3 次、个人中心说还剩 5 次"，两边都不会报错。
     */
    @GetMapping("/ai-quota")
    public ApiResponse<AiTaskQuota> aiQuota(
            Authentication authentication,
            HttpServletRequest request) {
        AccessTokenPrincipal principal = principal(authentication);
        return success(aiQuotas.quotaOf(principal.userId()), request);
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
