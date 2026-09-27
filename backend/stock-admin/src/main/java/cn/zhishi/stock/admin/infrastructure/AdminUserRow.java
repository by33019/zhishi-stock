package cn.zhishi.stock.admin.infrastructure;

import java.time.LocalDateTime;

/**
 * {@code sys_user} 的一行（原始值，未脱敏）。
 *
 * <p>脱敏发生在仓储把它映射成领域记录的时候，因此这个行对象不出 {@code infrastructure} 包。
 * 把它当成"内部传输对象"而不是领域模型是有意的：领域记录里没有明文字段，
 * 想泄露就得先改类型定义。
 */
public record AdminUserRow(
        long userId,
        String username,
        String password,
        String email,
        String phone,
        String realName,
        String nickName,
        int status,
        int tokenVersion,
        int createWhere,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        LocalDateTime lastLoginTime,
        int version) {
}
