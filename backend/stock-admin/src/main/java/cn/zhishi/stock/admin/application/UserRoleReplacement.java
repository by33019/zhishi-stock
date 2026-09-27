package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminUserRole;
import java.util.List;

/**
 * ADM-USR-06 的结果。
 *
 * @param roles               替换后的角色（回读自数据库，不是回显请求体：
 *                            请求里可能有重复 id 或顺序不同，回显会让"以为配上了"与
 *                            "真的写进去了"看起来一样）
 * @param revokedSessionCount 被置为 REVOKED 的刷新令牌条数
 * @param version             变更后的 {@code version}
 */
public record UserRoleReplacement(
        List<AdminUserRole> roles, int revokedSessionCount, int version) {

    public UserRoleReplacement {
        roles = List.copyOf(roles);
    }
}
