package cn.zhishi.stock.system.auth;

public record UserAccount(
        long id,
        String username,
        String passwordHash,
        Status status,
        String displayName) {

    public enum Status {
        ACTIVE,
        LOCKED,
        DISABLED
    }
}
