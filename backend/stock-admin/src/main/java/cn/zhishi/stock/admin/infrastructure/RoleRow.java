package cn.zhishi.stock.admin.infrastructure;

/** 角色列表的一行，含两个由 SQL 聚合出来的计数。 */
public record RoleRow(
        long roleId,
        String name,
        String description,
        int status,
        long userCount,
        long permissionCount,
        int version) {
}
