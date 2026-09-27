package cn.zhishi.stock.admin.application;

import cn.zhishi.stock.admin.domain.AdminUserStatus;

/**
 * ADM-USR-05 的结果。
 *
 * @param revokedSessionCount 被置为 REVOKED 的刷新令牌条数
 * @param version             变更后的 {@code version}（下一次 If-Match 用它）
 */
public record UserStatusChange(
        long userId, AdminUserStatus status, int revokedSessionCount, int version) {
}
