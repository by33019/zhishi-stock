package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.application.MaskedContact;
import cn.zhishi.stock.admin.domain.AdminUserDetail;
import cn.zhishi.stock.admin.domain.AdminUserPage;
import cn.zhishi.stock.admin.domain.AdminUserPatch;
import cn.zhishi.stock.admin.domain.AdminUserQuery;
import cn.zhishi.stock.admin.domain.AdminUserRole;
import cn.zhishi.stock.admin.domain.AdminUserStatus;
import cn.zhishi.stock.admin.domain.AdminUserStore;
import cn.zhishi.stock.admin.domain.AdminUserSummary;
import cn.zhishi.stock.admin.domain.NewAdminUser;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AdminUserStore} 的 MyBatis 实现。
 *
 * <h2>唯一的职责边界：脱敏在这里发生</h2>
 * 数据库行的明文邮箱 / 手机号在这里被换成脱敏值，之后它们不再出现在任何领域记录里
 * （见 {@code AdminUserDetail} 的说明）。这是本类最值得注意的一条约定：
 * 在别处脱敏都意味着明文先进了内存的更深处。
 *
 * <h2>时间与枚举的转换也在这里</h2>
 * 数据库列是 {@code datetime} / {@code tinyint}，领域侧是 {@code OffsetDateTime} /
 * 枚举。转换集中在 {@link #toOffsetDateTime} / {@link #toLocalDateTime} 与
 * {@link AdminUserStatus#fromDb} 三个点，避免"有的地方按 UTC 解释、有的按上海"。
 */
public class MyBatisAdminUserStore implements AdminUserStore {

    /** 契约 §16.1 ADM-USR-03：管理员在 Web 后台建号，{@code create_where} 记 Web。 */
    private static final int CREATE_WHERE_WEB = 1;

    private final AdminUserMapper mapper;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public MyBatisAdminUserStore(
            AdminUserMapper mapper, LongSupplier idGenerator, Clock clock) {
        this.mapper = mapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public AdminUserPage page(AdminUserQuery query) {
        String keyword = query.hasKeyword() ? query.keyword().trim() : null;
        Integer status = query.status() == null ? null : query.status().dbValue();
        LocalDateTime from = toLocalDateTime(query.createdStartAt());
        LocalDateTime to = toLocalDateTime(query.createdEndAt());
        List<AdminUserRow> rows = mapper.pageRows(
                keyword, status, query.roleId(), from, to, query.size(), query.offset());
        long total = mapper.countRows(keyword, status, query.roleId(), from, to);
        if (rows.isEmpty()) {
            // 不查角色：空页也发一次查询只会让"没有数据"变得更慢，且返回值一样。
            return new AdminUserPage(List.of(), total);
        }
        Map<Long, List<AdminUserRole>> rolesByUser = rolesByUser(rows);
        List<AdminUserSummary> items = new ArrayList<>(rows.size());
        for (AdminUserRow row : rows) {
            items.add(toSummary(row, rolesByUser.getOrDefault(row.userId(), List.of())));
        }
        return new AdminUserPage(items, total);
    }

    @Override
    public Optional<AdminUserDetail> find(long userId) {
        AdminUserRow row = mapper.find(userId);
        return row == null ? Optional.empty() : Optional.of(toDetail(row, rolesOf(userId)));
    }

    @Override
    public boolean usernameExists(String username) {
        return mapper.countByUsername(username) > 0;
    }

    @Override
    public boolean emailExists(String email, long excludingUserId) {
        return mapper.countByEmail(email, excludingUserId) > 0;
    }

    @Override
    @Transactional
    public long insert(NewAdminUser user) {
        long userId = idGenerator.getAsLong();
        mapper.insertUser(
                userId,
                user.username(),
                user.passwordHash(),
                user.nickName(),
                user.realName(),
                user.email(),
                user.phone(),
                user.status().dbValue(),
                CREATE_WHERE_WEB,
                user.operatorId());
        for (Long roleId : new LinkedHashSet<>(user.roleIds())) {
            mapper.insertUserRole(idGenerator.getAsLong(), userId, roleId);
        }
        return userId;
    }

    @Override
    public int updateProfile(
            long userId, AdminUserPatch patch, int expectedVersion, long operatorId) {
        return mapper.updateProfile(
                userId,
                patch.nickName(),
                patch.realName(),
                patch.email(),
                patch.phone(),
                expectedVersion,
                operatorId);
    }

    @Override
    public int updateStatus(
            long userId, AdminUserStatus status, int expectedVersion, long operatorId) {
        return mapper.updateStatus(userId, status.dbValue(), expectedVersion, operatorId);
    }

    @Override
    public int bumpTokenVersion(long userId, int expectedVersion, long operatorId) {
        return mapper.bumpTokenVersion(userId, expectedVersion, operatorId);
    }

    /**
     * 先删后插，同事务。
     *
     * <p>不用"算出差集再增删"：差集算法要在一次读的基础上做判断，而读与写之间的用户角色
     * 可能已经被别人改过——那时差集会算出错误的增删集合。整体替换的语义是"以这次请求为准"，
     * 与契约 ADM-USR-06 的"原子替换"一致。
     */
    @Override
    @Transactional
    public int replaceRoles(
            long userId, List<Long> roleIds, int expectedVersion, long operatorId) {
        // 版本守卫放在删除之前：软删角色是先破坏后重建，若最后才发现版本不对，
        // 事务虽然会回滚，但"先改再回滚"会让这段时间里的并发读者看到中间态。
        int affected = mapper.bumpTokenVersion(userId, expectedVersion, operatorId);
        if (affected == 0) {
            return 0;
        }
        mapper.deleteUserRoles(userId);
        for (Long roleId : new LinkedHashSet<>(roleIds)) {
            mapper.insertUserRole(idGenerator.getAsLong(), userId, roleId);
        }
        return affected;
    }

    @Override
    public int softDelete(long userId, int expectedVersion, long operatorId) {
        return mapper.softDelete(userId, expectedVersion, operatorId);
    }

    @Override
    public List<Long> invalidRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        Set<Long> requested = new LinkedHashSet<>(roleIds);
        List<Long> valid = mapper.findValidRoleIds(requested);
        List<Long> invalid = new ArrayList<>();
        for (Long roleId : requested) {
            if (!valid.contains(roleId)) {
                invalid.add(roleId);
            }
        }
        return invalid;
    }

    private List<AdminUserRole> rolesOf(long userId) {
        return mapper.findRoles(List.of(userId)).stream()
                .map(row -> new AdminUserRole(row.roleId(), row.name()))
                .toList();
    }

    private Map<Long, List<AdminUserRole>> rolesByUser(List<AdminUserRow> rows) {
        List<Long> userIds = rows.stream().map(AdminUserRow::userId).toList();
        Map<Long, List<AdminUserRole>> byUser = new HashMap<>();
        for (AdminUserRoleRow row : mapper.findRoles(userIds)) {
            byUser.computeIfAbsent(row.userId(), key -> new ArrayList<>())
                    .add(new AdminUserRole(row.roleId(), row.name()));
        }
        return byUser;
    }

    private AdminUserSummary toSummary(AdminUserRow row, List<AdminUserRole> roles) {
        return new AdminUserSummary(
                row.userId(),
                row.username(),
                MaskedContact.email(row.email()),
                MaskedContact.phone(row.phone()),
                row.nickName(),
                AdminUserStatus.fromDb(row.status()),
                roles,
                toOffsetDateTime(row.createTime()),
                toOffsetDateTime(row.lastLoginTime()),
                row.version());
    }

    private AdminUserDetail toDetail(AdminUserRow row, List<AdminUserRole> roles) {
        return new AdminUserDetail(
                row.userId(),
                row.username(),
                MaskedContact.email(row.email()),
                MaskedContact.phone(row.phone()),
                row.nickName(),
                row.realName(),
                AdminUserStatus.fromDb(row.status()),
                roles,
                row.createWhere(),
                toOffsetDateTime(row.createTime()),
                toOffsetDateTime(row.updateTime()),
                toOffsetDateTime(row.lastLoginTime()),
                row.tokenVersion(),
                row.version());
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
