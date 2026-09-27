package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminUserDetail;

/**
 * ADM-USR-03 的结果：新用户 + "必须改密"标记。
 *
 * <h2>{@code mustChangePassword} 恒为 {@code true}，且当前没有执行方</h2>
 * 契约要求管理员建的账号"临时密码 + 首次登录必须改密"。实现里**没有**对应的库列，
 * 也还没有 AUTH 侧的强制改密流程，所以这个字段是由创建流程本身决定的常量：
 * 管理员设了临时密码，此刻为真。
 *
 * <p>必须同时说明它的边界：**这一轮没有任何地方会读它**。用户登录后不会被强制改密。
 * 与其给一个永远没人消费的列（"看起来做了"），不如让它在接口上如实出现、
 * 在文档里如实说明。等 AUTH 的改密流程落地时，它需要的是新列 + 登录时的分支，
 * 而不是把这个常量改成读数据库。
 */
public record CreatedAdminUser(AdminUserDetail user, boolean mustChangePassword) {

    public static CreatedAdminUser of(AdminUserDetail user) {
        return new CreatedAdminUser(user, true);
    }
}
