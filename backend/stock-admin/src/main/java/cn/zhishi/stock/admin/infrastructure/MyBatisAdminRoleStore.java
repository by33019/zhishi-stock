package cn.zhishi.stock.admin.infrastructure;

import cn.zhishi.stock.admin.domain.AdminRoleStore;
import cn.zhishi.stock.admin.domain.RolePage;
import cn.zhishi.stock.admin.domain.RoleQuery;
import cn.zhishi.stock.admin.domain.RoleSummary;
import java.util.OptionalLong;

/**
 * {@link AdminRoleStore} 的 MyBatis 实现。
 *
 * <p>超管角色名由配置注入（{@code stock.admin.super-role-name}，默认 {@code ADMIN}），
 * 因为"哪个角色算超管"是部署决策而不是代码决策：写死在 SQL 里，
 * 改一个角色名就需要重新编译。
 */
public class MyBatisAdminRoleStore implements AdminRoleStore {

    private final AdminRoleMapper mapper;
    private final String superRoleName;

    public MyBatisAdminRoleStore(AdminRoleMapper mapper, String superRoleName) {
        this.mapper = mapper;
        this.superRoleName = superRoleName;
    }

    @Override
    public RolePage page(RoleQuery query) {
        String keyword = query.hasKeyword() ? query.keyword().trim() : null;
        var rows = mapper.pageRows(keyword, query.status(), query.size(), query.offset());
        long total = mapper.countRows(keyword, query.status());
        return new RolePage(
                rows.stream()
                        .map(row -> new RoleSummary(
                                row.roleId(),
                                row.name(),
                                row.description(),
                                row.status(),
                                row.userCount(),
                                row.permissionCount(),
                                row.version()))
                        .toList(),
                total);
    }

    /**
     * 超管角色的 id。
     *
     * <p>用 {@code OptionalLong} 而不是"找不到就返回 0 或 -1"：找不到是一个**有意义的状态**
     * ——它意味着这套库里没有任何角色叫这个名字，此时任何超管保护判定都不成立。
     * 用一个哨兵值代替，会让"角色被改名了"看起来像"有一个 id 为 0 的角色"。
     */
    @Override
    public OptionalLong superAdminRoleId() {
        Long roleId = mapper.findRoleIdByName(superRoleName);
        return roleId == null ? OptionalLong.empty() : OptionalLong.of(roleId);
    }

    @Override
    public boolean isActiveSuperAdmin(long userId) {
        return superAdminRoleId()
                .stream()
                .anyMatch(roleId -> mapper.isActiveSuperAdmin(userId, roleId) > 0);
    }

    @Override
    public long countActiveSuperAdmins() {
        return superAdminRoleId()
                .stream()
                .map(mapper::countActiveSuperAdmins)
                .findFirst()
                .orElse(0L);
    }
}
