package cn.zhishi.stock.admin.domain;

/**
 * 用户身上的一条角色关系（角色 id + 名称）。
 *
 * <p>名称一起带上而不是只给 id：后台列表要显示"这个用户是 ADMIN"，再查一次角色表
 * 会让每页多 N 次查询；而只给 id 等于把"角色叫什么"这件事实推给前端去猜。
 */
public record AdminUserRole(long roleId, String name) {
}
