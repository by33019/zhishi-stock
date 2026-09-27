package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminRoleStore;
import cn.zhishi.stock.admin.domain.AdminUserDetail;
import cn.zhishi.stock.admin.domain.AdminUserPatch;
import cn.zhishi.stock.admin.domain.AdminUserQuery;
import cn.zhishi.stock.admin.domain.AdminUserRole;
import cn.zhishi.stock.admin.domain.AdminUserStore;
import cn.zhishi.stock.admin.domain.AdminUserStatus;
import cn.zhishi.stock.admin.domain.AdminUserSummary;
import cn.zhishi.stock.admin.domain.NewAdminUser;
import cn.zhishi.stock.system.auth.PasswordResetCredentialStore;
import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.system.auth.OpaqueTokenGenerator;
import cn.zhishi.stock.system.auth.RefreshSessionStore;
import cn.zhishi.stock.system.auth.RefreshTokenHashing;
import cn.zhishi.stock.system.watchlist.WatchlistGroupService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台用户管理用例（契约 §16.1 ADM-USR-01~09）。
 *
 * <h2>撤权三件套：为什么每次都要一起做</h2>
 * 锁定 / 换角色 / 删除用户时都会走同一条链：
 * <ol>
 *   <li>递增 {@code sys_user.token_version}——权限码烧在 access token 里，
 *       只有版本变了，{@code JwtAuthenticationFilter} 才会在**下一个请求**上拒掉旧令牌；</li>
 *   <li>撤销该用户的全部刷新会话——不然被撤权者能用刷新令牌换到一张"版本已经是新的"
 *       新令牌，撤销只挡住了旧令牌；</li>
 *   <li>把 {@code version} 也递增——它是 If-Match 的依据，一次变更没动它，
 *       别人拿着旧版本号还能再改一次。</li>
 * </ol>
 *
 * <h2>版本号是链式推演的</h2>
 * 每次写库把 {@code version} 加 1，因此用例层从读到的 {@code v0} 起推
 * {@code v0+1, v0+2 …} 依次作为下次写的前置条件。任何一步影响 0 行都说明有人在中间改过，
 * 此时回读一次给出真实的当前版本（而不是编一个）。最后回读一次作为响应里的新 {@code version}。
 *
 * <h2>超级管理员保护是假设</h2>
 * 契约只说"不得锁定 / 删除最后一个超级管理员"，没定义谁是超管。
 * 本实现取"持有 {@code stock.admin.super-role-name}（默认 {@code ADMIN}）角色的启用用户"，
 * 判据见 {@link SuperAdminRule}。
 */
public class AdminUserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminUserService.class);

    /** ADM-USR-07 的 delivery 目前只有 EMAIL（契约原文）。 */
    private static final String DELIVERY_EMAIL = "EMAIL";

    private final AdminUserStore users;
    private final AdminRoleStore roles;
    private final RefreshSessionStore sessions;
    private final PasswordResetCredentialStore resetCredentials;
    private final WatchlistGroupService watchlistGroups;
    private final PasswordEncoder passwordEncoder;
    private final OpaqueTokenGenerator tokenGenerator;
    private final Clock clock;

    public AdminUserService(
            AdminUserStore users,
            AdminRoleStore roles,
            RefreshSessionStore sessions,
            PasswordResetCredentialStore resetCredentials,
            WatchlistGroupService watchlistGroups,
            PasswordEncoder passwordEncoder,
            OpaqueTokenGenerator tokenGenerator,
            Clock clock) {
        this.users = users;
        this.roles = roles;
        this.sessions = sessions;
        this.resetCredentials = resetCredentials;
        this.watchlistGroups = watchlistGroups;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    // ---------- ADM-USR-01 ----------

    public PageData<AdminUserSummary> list(AdminUserQuery query) {
        var page = users.page(query);
        return new PageData<>(
                page.items(),
                query.page(),
                query.size(),
                page.total(),
                (int) ((page.total() + query.size() - 1) / query.size()),
                (long) query.page() * query.size() < page.total());
    }

    // ---------- ADM-USR-02 ----------

    public AdminUserDetail detail(long userId) {
        return require(userId);
    }

    // ---------- ADM-USR-03 ----------

    /**
     * 建号。
     *
     * <p>临时密码在这里哈希，明文不进入 {@link NewAdminUser}，也不进入任何返回值。
     * 账号唯一性的判断放在插入前，但**不依赖它**：真并发下仍可能撞
     * {@code unique_username}，那时由数据库报错、全局处理器返回 409（与这里的
     * 前置检查同一个码），而不是让两条并发请求都以为自己成功了。
     */
    @Transactional
    public CreatedAdminUser create(
            String username,
            String rawPassword,
            String nickName,
            String realName,
            String email,
            String phone,
            AdminUserStatus status,
            List<Long> roleIds,
            long operatorId) {
        if (users.usernameExists(username)) {
            throw AdminException.usernameExists(username);
        }
        if (email != null && !email.isBlank() && users.emailExists(email, 0L)) {
            throw AdminException.emailExists(email);
        }
        requireRolesExist(roleIds);
        long userId;
        try {
            userId = users.insert(new NewAdminUser(
                    username,
                    passwordEncoder.encode(rawPassword),
                    nickName,
                    realName,
                    blankToNull(email),
                    blankToNull(phone),
                    status,
                    List.copyOf(new HashSet<>(roleIds)),
                    operatorId));
        } catch (DuplicateKeyException exception) {
            // 并发下唯一约束才是最终判据：前置检查与插入之间的窗口里，别人可能刚占了同一个账号
            // 或邮箱。这里不解析数据库的报错文本（它随驱动版本与语言变化），
            // 而是回查一次——证据来自数据本身，翻成哪个码就有据可依。
            if (users.usernameExists(username)) {
                throw AdminException.usernameExists(username);
            }
            if (email != null && !email.isBlank() && users.emailExists(email, 0L)) {
                throw AdminException.emailExists(email);
            }
            throw exception;
        }
        // 与契约 §12.3 的注册流程保持一致：新账号应当有一个默认自选分组，
        // 否则用户第一次进 /watchlist 看到的是"一个分组都没有"的空状态。
        // 它失败不回滚账号：账号是主要事实，分组缺失是次要的可修复状态。
        try {
            watchlistGroups.createDefaultGroup(userId);
        } catch (RuntimeException exception) {
            LOGGER.error("为新账号创建默认自选分组失败：userId={}", userId, exception);
        }
        return CreatedAdminUser.of(require(userId));
    }

    // ---------- ADM-USR-04 ----------

    /** 改资料。不改密码、角色与逻辑删除状态（契约 ADM-USR-04 明写）。 */
    @Transactional
    public AdminUserDetail updateProfile(
            long userId, AdminUserPatch patch, int expectedVersion, long operatorId) {
        AdminUserDetail current = require(userId);
        requireVersion(current.version(), expectedVersion);
        // 空串按"不改"处理（与 null 同义）：PATCH 无法表达清空，见 AdminUserPatch 的说明。
        // 归一化之后仍为空，说明请求体里要么没有字段、要么全是空白——两者都是"没有可改的东西"，
        // 而不是一次成功的空更新（那会白白把 version 推进一格）。
        AdminUserPatch normalized = new AdminUserPatch(
                blankToNull(patch.nickName()),
                blankToNull(patch.realName()),
                blankToNull(patch.email()),
                blankToNull(patch.phone()));
        if (normalized.isEmpty()) {
            throw AdminException.invalidRequest("请求体里没有任何可修改的字段");
        }
        if (normalized.email() != null && users.emailExists(normalized.email(), userId)) {
            throw AdminException.emailExists(normalized.email());
        }
        applyOrConflict(
                users.updateProfile(userId, normalized, expectedVersion, operatorId),
                userId,
                expectedVersion);
        return require(userId);
    }

    // ---------- ADM-USR-05 ----------

    /**
     * 锁定 / 解锁。
     *
     * <p>锁定时撤权三件套全走；解锁只改状态——刚被解锁的人本来就该重新登录
     * （他的旧令牌在锁定时已经废了），再递增一次版本没有意义。
     */
    @Transactional
    public UserStatusChange changeStatus(
            long userId, AdminUserStatus status, int expectedVersion, long operatorId) {
        AdminUserDetail current = require(userId);
        requireVersion(current.version(), expectedVersion);
        boolean locksOut = status == AdminUserStatus.LOCKED;
        if (locksOut) {
            requireNotLastSuperAdmin(current);
        }
        applyOrConflict(
                users.updateStatus(userId, status, expectedVersion, operatorId),
                userId,
                expectedVersion);
        int version = expectedVersion + 1;
        int revoked = 0;
        if (locksOut) {
            applyOrConflict(
                    users.bumpTokenVersion(userId, version, operatorId), userId, version);
            version++;
            revoked = sessions.revokeAllForUser(userId);
        }
        return new UserStatusChange(userId, status, revoked, version);
    }

    // ---------- ADM-USR-06 ----------

    /** 原子替换角色，并把这次授权变更立刻烧进令牌版本。 */
    @Transactional
    public UserRoleReplacement replaceRoles(
            long userId, List<Long> roleIds, int expectedVersion, long operatorId) {
        AdminUserDetail current = require(userId);
        requireVersion(current.version(), expectedVersion);
        List<Long> unique = List.copyOf(new HashSet<>(roleIds));
        requireRolesExist(unique);
        OptionalLong superRoleId = roles.superAdminRoleId();
        if (superRoleId.isPresent()) {
            long superId = superRoleId.getAsLong();
            boolean removesSuperRole = current.hasRole(superId) && !unique.contains(superId);
            if (removesSuperRole) {
                requireNotLastSuperAdmin(current);
            }
        }
        applyOrConflict(
                users.replaceRoles(userId, unique, expectedVersion, operatorId),
                userId,
                expectedVersion);
        // 递增令牌版本由 replaceRoles 在同一事务里完成（换角色必须立刻让旧权限码失效，
        // 见 AdminUserStore#replaceRoles），这里只推进版本链并撤销刷新会话。
        int version = expectedVersion + 1;
        int revoked = sessions.revokeAllForUser(userId);
        return new UserRoleReplacement(rolesOf(userId), revoked, version);
    }

    // ---------- ADM-USR-07 ----------

    /**
     * 生成一次性密码重置凭证。
     *
     * <p>只存 SHA-256，不存明文，也没有读回接口——契约明写"不允许管理员读取或指定
     * 用户最终密码"。凭证明文生成后即被丢弃：项目没有邮件通道，因此本轮它也送不出去，
     * 所以响应里 {@code delivered=false} 并给出原因，而不是只回一个 {@code accepted=true}。
     */
    public PasswordResetIssued requestPasswordReset(long userId, String delivery) {
        AdminUserDetail current = require(userId);
        if (!DELIVERY_EMAIL.equalsIgnoreCase(delivery)) {
            throw AdminException.invalidRequest("delivery 目前只支持 EMAIL");
        }
        if (!current.emailConfigured()) {
            throw AdminException.passwordResetNoDeliveryTarget();
        }
        // verificationId 与 credential 用同一生成器连出两枚：前者是签发标识
        // （随邮件一起送达用户，兑换时原样带回），后者是一次性秘密（只存哈希）。
        // 测试注入受控生成器即可同时拿到两者，完整兑换链路因此可测。
        String verificationId = tokenGenerator.next();
        String credential = tokenGenerator.next();
        Instant expiresAt = clock.instant().plusSeconds(PasswordResetCredentialStore.TTL_SECONDS);
        resetCredentials.save(userId, verificationId, RefreshTokenHashing.sha256(credential), expiresAt);
        return new PasswordResetIssued(
                true,
                current.maskedEmail(),
                PasswordResetCredentialStore.TTL_SECONDS,
                false,
                "凭证已生成，但项目尚未接入邮件通道，未实际送达用户；"
                        + "请由管理员通过客服渠道线下转达，或等邮件通道就绪后重试。");
    }

    // ---------- ADM-USR-08 ----------

    /**
     * 强制下线。
     *
     * <p>没有 If-Match：契约给 ADM-USR-08 的是 {@code Idempotency-Key}。
     * 重复调用是安全的——第一遍之后没有 ACTIVE 令牌可撤，计数自然变成 0。
     */
    @Transactional
    public SessionRevocation revokeSessions(long userId, long operatorId) {
        AdminUserDetail current = require(userId);
        int expectedVersion = current.version();
        applyOrConflict(
                users.bumpTokenVersion(userId, expectedVersion, operatorId),
                userId,
                expectedVersion);
        int revoked = sessions.revokeAllForUser(userId);
        return new SessionRevocation(revoked, current.tokenVersion() + 1);
    }

    // ---------- ADM-USR-09 ----------

    /**
     * 逻辑删除（契约要求的 {@code deleted=0} 反向语义）。
     *
     * <p>两条硬约束：不能删自己（删完就没人能再操作了），不能删最后一个超管。
     * 顺序是"先撤权再删"——反过来的话，{@code deleted=0} 会让后面的
     * {@code bumpTokenVersion} 找不到行（它的条件里有 {@code deleted = 1}），
     * 于是被删的人还能拿旧令牌跑满 15 分钟。
     */
    @Transactional
    public UserDeletion delete(long userId, int expectedVersion, long operatorId) {
        AdminUserDetail current = require(userId);
        requireVersion(current.version(), expectedVersion);
        if (userId == operatorId) {
            throw AdminException.selfOperationForbidden("删除");
        }
        requireNotLastSuperAdmin(current);
        int version = expectedVersion;
        applyOrConflict(users.bumpTokenVersion(userId, version, operatorId), userId, version);
        version++;
        int revoked = sessions.revokeAllForUser(userId);
        applyOrConflict(users.softDelete(userId, version, operatorId), userId, version);
        return new UserDeletion(true, revoked);
    }

    // ---------- 共用判定 ----------

    private AdminUserDetail require(long userId) {
        return users.find(userId).orElseThrow(AdminException::userNotFound);
    }

    private List<AdminUserRole> rolesOf(long userId) {
        return require(userId).roles();
    }

    /**
     * 前置的版本比对。
     *
     * <p>放在写之前是为了给出**准确的当前版本**（回读一次即可），
     * 而不是等写回 0 行之后再猜。写回 0 行仍然要处理：并发下前置检查随时会过期。
     */
    private void requireVersion(int currentVersion, int expectedVersion) {
        if (currentVersion != expectedVersion) {
            throw AdminException.versionConflict(currentVersion);
        }
    }

    /** 写回 0 行 = 期间有人改过。回读给出真实版本；连记录都没了就报 404。 */
    private void applyOrConflict(int affected, long userId, int expectedVersion) {
        if (affected > 0) {
            return;
        }
        AdminUserDetail latest = users.find(userId).orElse(null);
        if (latest == null) {
            throw AdminException.userNotFound();
        }
        throw AdminException.versionConflict(latest.version());
    }

    private void requireNotLastSuperAdmin(AdminUserDetail target) {
        boolean targetIsSuperAdmin = roles.isActiveSuperAdmin(target.userId());
        long count = targetIsSuperAdmin ? roles.countActiveSuperAdmins() : 0L;
        if (SuperAdminRule.blocks(count, targetIsSuperAdmin, true)) {
            throw AdminException.lastSuperAdminProtected();
        }
    }

    private void requireRolesExist(List<Long> roleIds) {
        List<Long> invalid = users.invalidRoleIds(roleIds);
        if (!invalid.isEmpty()) {
            throw AdminException.roleNotFound(invalid.toString());
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
