package cn.zhishi.stock.admin.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 后台用户列表的一行（契约 §16.1 ADM-USR-01 的返回字段）。
 *
 * <h2>联系方式在这里就已是脱敏的</h2>
 * {@code maskedEmail} / {@code maskedPhone} 是**已经脱敏**的值：脱敏发生在仓储
 * 把数据库行映射成这个记录的时候，因此未脱敏的值不会进入领域层，也就不可能被
 * 某个将来的日志、异常消息或响应体顺手带出去。契约允许"由单独权限控制是否展示
 * 未脱敏联系方式"，本轮的权限集里没有那个码，所以恒为脱敏值（见 {@code MaskedContact}）。
 *
 * <h2>没有 password / tokenVersion</h2>
 * 契约明写"普通管理员看不到密码、Token"。不是"前端不渲染"，而是这一层就没有这两个字段——
 * 少一个字段就少一条泄露路径，且不依赖调用方记得过滤。
 */
public record AdminUserSummary(
        long userId,
        String username,
        String maskedEmail,
        String maskedPhone,
        String nickName,
        AdminUserStatus status,
        List<AdminUserRole> roles,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginTime,
        int version) {

    public AdminUserSummary {
        roles = List.copyOf(roles);
    }
}
