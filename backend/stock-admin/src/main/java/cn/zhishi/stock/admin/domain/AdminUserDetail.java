package cn.zhishi.stock.admin.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 后台用户详情（契约 §16.1 ADM-USR-02 的返回字段）。
 *
 * <p>比列表多出的字段每一项都有明确用途：{@code tokenVersion} 供管理员确认"强制下线确实生效了"
 * （它在锁定 / 撤销会话后必然递增），{@code version} 是 If-Match 乐观锁的当前值，
 * {@code createWhere} 是"创建来源"。
 *
 * <p>契约 §16.1 末段要求不把反向逻辑删除字段 {@code deleted=1/0} 暴露给前端，
 * 因此这里没有它——"已删除"表现为查询不到（所有查询都带 {@code deleted = 1}）。
 */
public record AdminUserDetail(
        long userId,
        String username,
        String maskedEmail,
        String maskedPhone,
        String nickName,
        String realName,
        AdminUserStatus status,
        List<AdminUserRole> roles,
        Integer createWhere,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastLoginTime,
        int tokenVersion,
        int version) {

    public AdminUserDetail {
        roles = List.copyOf(roles);
    }

    public List<Long> roleIds() {
        return roles.stream().map(AdminUserRole::roleId).toList();
    }

    public boolean hasRole(long roleId) {
        return roles.stream().anyMatch(role -> role.roleId() == roleId);
    }

    /**
     * 是否配置了可用于投递的邮箱（ADM-USR-07 的投递前提）。
     *
     * <p>由 {@code maskedEmail} 推导而不是另存一个字段：脱敏对空值恰好回 {@code null}，
     * 因此"能投递"与"有脱敏值"是同一件事。另存一个布尔就会有两处可能不一致的答案。
     */
    public boolean emailConfigured() {
        return maskedEmail != null;
    }
}
