package cn.zhishi.stock.admin.domain;

import java.util.List;

/**
 * 待创建的账号（契约 §16.1 ADM-USR-03）。
 *
 * <p>{@code passwordHash} 已是 BCrypt 密文：哈希在用例层完成，仓储只负责写。
 * 明文密码不进入这个记录，也不进入任何返回值——契约要求"临时密码不出现在响应和日志中"，
 * 而最可靠的实现方式是让它根本没有机会被带回去。
 *
 * @param operatorId 操作人（写进 {@code create_id}，用于审计"谁建的号"）
 */
public record NewAdminUser(
        String username,
        String passwordHash,
        String nickName,
        String realName,
        String email,
        String phone,
        AdminUserStatus status,
        List<Long> roleIds,
        long operatorId) {
}
