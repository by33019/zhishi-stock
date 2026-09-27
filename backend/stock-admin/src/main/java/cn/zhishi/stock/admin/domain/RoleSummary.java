package cn.zhishi.stock.admin.domain;

/**
 * 角色列表的一行（契约 §16.2 ADM-ROL-01 的返回字段）。
 *
 * <p>{@code userCount} / {@code permissionCount} 由 SQL 聚合得出，不是前端另发两次请求
 * 数出来的：让前端数，就要把用户列表与权限列表都放开，而那两个列表本身并不需要开放。
 */
public record RoleSummary(
        long roleId,
        String name,
        String description,
        int status,
        long userCount,
        long permissionCount,
        int version) {
}
