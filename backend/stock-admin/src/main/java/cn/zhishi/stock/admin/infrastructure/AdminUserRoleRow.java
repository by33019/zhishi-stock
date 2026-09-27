package cn.zhishi.stock.admin.infrastructure;

/** {@code sys_user_role} ⋈ {@code sys_role} 的一行：某个用户的一个角色。 */
public record AdminUserRoleRow(long userId, long roleId, String name) {
}
