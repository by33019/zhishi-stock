package cn.zhishi.stock.system.auth;

public record SysUserRecord(
        long id,
        String username,
        String passwordHash,
        String displayName,
        int status,
        int tokenVersion) {
}
