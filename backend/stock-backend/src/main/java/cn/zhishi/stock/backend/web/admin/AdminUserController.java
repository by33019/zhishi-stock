package cn.zhishi.stock.backend.web.admin;

import cn.zhishi.stock.admin.application.AdminUserService;
import cn.zhishi.stock.admin.application.CreatedAdminUser;
import cn.zhishi.stock.admin.application.PasswordResetIssued;
import cn.zhishi.stock.admin.application.SessionRevocation;
import cn.zhishi.stock.admin.application.UserDeletion;
import cn.zhishi.stock.admin.application.UserRoleReplacement;
import cn.zhishi.stock.admin.application.UserStatusChange;
import cn.zhishi.stock.admin.domain.AdminUserDetail;
import cn.zhishi.stock.admin.domain.AdminUserPatch;
import cn.zhishi.stock.admin.domain.AdminUserQuery;
import cn.zhishi.stock.admin.domain.AdminUserStatus;
import cn.zhishi.stock.admin.domain.AdminUserSummary;
import cn.zhishi.stock.backend.web.AuditRecorder;
import cn.zhishi.stock.backend.web.IfMatch;
import cn.zhishi.stock.backend.web.TraceIdFilter;
import cn.zhishi.stock.common.api.ApiResponse;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.idempotency.IdempotencyGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台用户管理（契约 §16.1 ADM-USR-01~09）。
 *
 * <h2>每个端点一个权限码</h2>
 * 路径级规则只能表达"/api/v1/admin 下都要已登录"，而契约给的是**每个端点一个**权限标识
 * （{@code sys:user:list} 与 {@code sys:user:delete} 不是同一件事）。因此 9 个方法各带一个
 * {@code @PreAuthorize}，由 {@code SecurityConfiguration} 上的 {@code @EnableMethodSecurity} 生效。
 * 权限码来自 access token 的 {@code permissions} claim——与登录时返回给前端的是同一批，
 * 因此"页面上看得见"和"接口能调通"用的是同一个事实。
 *
 * <h2>返回体直接给领域记录</h2>
 * {@code AdminUserSummary} / {@code AdminUserDetail} 的字段名与契约表格逐项对齐，
 * 再套一层 View 只会多一处"改了领域字段忘了改 View"的机会。字段确实多于契约的地方
 * （{@code version} 供 If-Match、{@code tokenVersion} 供确认撤权生效）都是契约要求的。
 *
 * <h2>写操作全部进审计</h2>
 * 7 个写操作各记一条 {@code sys_log}，失败与被拒也记（见 {@link AdminAudit}）。
 * 密码类字段永不入日志：摘要按白名单逐字段拼。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    /** 幂等范围（契约 §3.7）：同一管理员在同一范围内键唯一。 */
    private static final String CREATE_SCOPE = "admin-user:create";
    private static final String PASSWORD_RESET_SCOPE = "admin-user:password-reset";
    private static final String SESSION_REVOKE_SCOPE = "admin-user:session-revoke";

    private final AdminUserService users;
    private final IdempotencyGuard idempotency;
    private final AuditRecorder audit;
    private final Clock clock;

    public AdminUserController(
            AdminUserService users,
            IdempotencyGuard idempotency,
            AuditRecorder audit,
            Clock clock) {
        this.users = users;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    /** ADM-USR-01：分页查询未物理删除的用户。 */
    @GetMapping
    @PreAuthorize("hasAuthority('sys:user:list')")
    public ApiResponse<PageData<AdminUserSummary>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) AdminUserStatus status,
            @RequestParam(value = "roleId", required = false) Long roleId,
            @RequestParam(value = "createdStartAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdStartAt,
            @RequestParam(value = "createdEndAt", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdEndAt,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        PageData<AdminUserSummary> pageData = users.list(new AdminUserQuery(
                keyword, status, roleId, createdStartAt, createdEndAt, page, size));
        return success(pageData, request);
    }

    /** ADM-USR-02：查看单个用户的管理信息（联系方式始终是脱敏值）。 */
    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('sys:user:detail')")
    public ApiResponse<AdminUserDetail> detail(
            @PathVariable long userId, HttpServletRequest request) {
        return success(users.detail(userId), request);
    }

    /** ADM-USR-03：建号。临时密码只在这一次请求里出现，不进响应、不进日志。 */
    @PostMapping
    @PreAuthorize("hasAuthority('sys:user:create')")
    public ApiResponse<CreatedAdminUser> create(
            Authentication authentication,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateUserRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        CreatedAdminUser created = audited(
                authentication,
                request,
                AdminAudit.USER_CREATE,
                AdminAudit.summary(
                        "username=" + body.username(),
                        "status=" + body.status(),
                        "roleIds=" + body.roleIds()),
                AdminAudit::failureStatus,
                () -> idempotency.execute(
                        CREATE_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        body,
                        CreatedAdminUser.class,
                        () -> users.create(
                                body.username(),
                                body.password(),
                                body.nickName(),
                                body.realName(),
                                body.email(),
                                body.phone(),
                                body.status(),
                                body.roleIds(),
                                operatorId)));
        return success(created, request);
    }

    /** ADM-USR-04：改资料。不允许通过它改密码、角色与逻辑删除状态。 */
    @PatchMapping("/{userId}")
    @PreAuthorize("hasAuthority('sys:user:update')")
    public ApiResponse<AdminUserDetail> updateProfile(
            Authentication authentication,
            @PathVariable long userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody UpdateProfileRequest body,
            HttpServletRequest request) {
        AdminUserDetail updated = audited(
                authentication,
                request,
                AdminAudit.USER_UPDATE,
                AdminAudit.summary("userId=" + userId, "fields=" + body.changedFields()),
                AdminAudit::failureStatus,
                () -> users.updateProfile(
                        userId,
                        new AdminUserPatch(
                                body.nickName(), body.realName(), body.email(), body.phone()),
                        ifMatchVersion(ifMatch),
                        principal(authentication).userId()));
        return success(updated, request);
    }

    /** ADM-USR-05：锁定 / 解锁。锁定时递增令牌版本并撤销全部刷新会话。 */
    @PatchMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('sys:user:status')")
    public ApiResponse<UserStatusChange> changeStatus(
            Authentication authentication,
            @PathVariable long userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody ChangeStatusRequest body,
            HttpServletRequest request) {
        UserStatusChange changed = audited(
                authentication,
                request,
                AdminAudit.USER_STATUS_CHANGE,
                AdminAudit.summary(
                        "userId=" + userId,
                        "status=" + body.status(),
                        AdminAudit.pair("reason", body.reason())),
                AdminAudit::failureStatus,
                () -> users.changeStatus(
                        userId,
                        body.status(),
                        ifMatchVersion(ifMatch),
                        principal(authentication).userId()));
        return success(changed, request);
    }

    /** ADM-USR-06：原子替换角色。 */
    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('sys:user:role')")
    public ApiResponse<UserRoleReplacement> replaceRoles(
            Authentication authentication,
            @PathVariable long userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody ReplaceRolesRequest body,
            HttpServletRequest request) {
        UserRoleReplacement replaced = audited(
                authentication,
                request,
                AdminAudit.USER_ROLE_REPLACE,
                AdminAudit.summary("userId=" + userId, "roleIds=" + body.roleIds()),
                AdminAudit::failureStatus,
                () -> users.replaceRoles(
                        userId,
                        body.roleIds(),
                        ifMatchVersion(ifMatch),
                        principal(authentication).userId()));
        return success(replaced, request);
    }

    /**
     * ADM-USR-07：生成一次性密码重置凭证。
     *
     * <p>响应里的 {@code delivered} 恒为 {@code false} 并附带原因——
     * 项目还没有邮件通道。这一点写在响应里而不是只写在文档里：
     * 只看 {@code accepted=true} 的调用方会以为用户马上会收到邮件。
     */
    @PostMapping("/{userId}/password-reset")
    @PreAuthorize("hasAuthority('sys:user:password-reset')")
    public ApiResponse<PasswordResetIssued> requestPasswordReset(
            Authentication authentication,
            @PathVariable long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PasswordResetRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        PasswordResetIssued issued = audited(
                authentication,
                request,
                AdminAudit.USER_PASSWORD_RESET,
                AdminAudit.summary(
                        "userId=" + userId,
                        "delivery=" + body.delivery(),
                        AdminAudit.pair("reason", body.reason())),
                AdminAudit::failureStatus,
                () -> idempotency.execute(
                        PASSWORD_RESET_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        body,
                        PasswordResetIssued.class,
                        () -> users.requestPasswordReset(userId, body.delivery())));
        return success(issued, request);
    }

    /** ADM-USR-08：强制全部下线。 */
    @PostMapping("/{userId}/sessions/revoke")
    @PreAuthorize("hasAuthority('sys:user:session-revoke')")
    public ApiResponse<SessionRevocation> revokeSessions(
            Authentication authentication,
            @PathVariable long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RevokeSessionsRequest body,
            HttpServletRequest request) {
        long operatorId = principal(authentication).userId();
        SessionRevocation revoked = audited(
                authentication,
                request,
                AdminAudit.USER_SESSION_REVOKE,
                AdminAudit.summary(
                        "userId=" + userId,
                        AdminAudit.pair("reason", body == null ? null : body.reason())),
                AdminAudit::failureStatus,
                () -> idempotency.execute(
                        SESSION_REVOKE_SCOPE,
                        operatorId,
                        IdempotencyGuard.requireKey(idempotencyKey),
                        body == null ? new RevokeSessionsRequest(null) : body,
                        SessionRevocation.class,
                        () -> users.revokeSessions(userId, operatorId)));
        return success(revoked, request);
    }

    /** ADM-USR-09：逻辑删除（{@code deleted} 置为 0）。 */
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('sys:user:delete')")
    public ApiResponse<UserDeletion> delete(
            Authentication authentication,
            @PathVariable long userId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) DeleteUserRequest body,
            HttpServletRequest request) {
        UserDeletion deleted = audited(
                authentication,
                request,
                AdminAudit.USER_DELETE,
                AdminAudit.summary(
                        "userId=" + userId,
                        AdminAudit.pair("reason", body == null ? null : body.reason())),
                AdminAudit::failureStatus,
                () -> users.delete(
                        userId, ifMatchVersion(ifMatch), principal(authentication).userId()));
        return success(deleted, request);
    }

    // ---------- 共用 ----------

    /**
     * {@code If-Match} 的解析。
     *
     * <p>用 {@code IfMatch} 而不是在每个端点里手写：缺失 / 非法 / 带引号三种形态
     * 各写一遍，第三次就会漏掉一种，而"漏掉"的表现是乐观锁静默失效。
     */
    private static int ifMatchVersion(String header) {
        return IfMatch.version(header);
    }

    private <T> T audited(
            Authentication authentication,
            HttpServletRequest request,
            String operation,
            String paramsSummary,
            Function<RuntimeException, String> failureStatus,
            Supplier<T> action) {
        return audit.audited(
                authentication, request, operation, paramsSummary, failureStatus, action);
    }

    private static AccessTokenPrincipal principal(Authentication authentication) {
        return (AccessTokenPrincipal) authentication.getPrincipal();
    }

    private <T> ApiResponse<T> success(T data, HttpServletRequest request) {
        return ApiResponse.success(
                data, TraceIdFilter.current(request),
                OffsetDateTime.now(clock));
    }

    // ---------- 请求体 ----------

    /**
     * ADM-USR-03 的请求体。
     *
     * <p>{@code password} 只在这里出现一次，之后立刻变成 BCrypt 密文（在用例层）。
     * 它没有 getter 之外的任何出口，也不参与 {@link AdminAudit#summary}——
     * 摘要由调用方逐字段拼，序列化整个 body 是绝不允许的。
     */
    public record CreateUserRequest(
            String username,
            String password,
            String nickName,
            String realName,
            String email,
            String phone,
            AdminUserStatus status,
            List<Long> roleIds) {

        public CreateUserRequest {
            status = status == null ? AdminUserStatus.ACTIVE : status;
            roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        }
    }

    public record UpdateProfileRequest(
            String nickName, String realName, String email, String phone) {

        /** 只列出被赋值的字段名，供审计用；值本身可能含个人信息，不进摘要。 */
        List<String> changedFields() {
            List<String> fields = new java.util.ArrayList<>();
            if (nickName != null) {
                fields.add("nickName");
            }
            if (realName != null) {
                fields.add("realName");
            }
            if (email != null) {
                fields.add("email");
            }
            if (phone != null) {
                fields.add("phone");
            }
            return fields;
        }
    }

    public record ChangeStatusRequest(AdminUserStatus status, String reason) {
    }

    public record ReplaceRolesRequest(List<Long> roleIds) {

        public ReplaceRolesRequest {
            roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        }
    }

    public record PasswordResetRequest(String delivery, String reason) {

        public PasswordResetRequest {
            delivery = delivery == null ? "" : delivery;
        }
    }

    public record RevokeSessionsRequest(String reason) {
    }

    public record DeleteUserRequest(String reason) {
    }
}
