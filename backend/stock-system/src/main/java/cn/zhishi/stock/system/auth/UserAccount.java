package cn.zhishi.stock.system.auth;

public record UserAccount(
        long id,
        String username,
        String passwordHash,
        Status status,
        String displayName,
        int tokenVersion) {

    public UserAccount(
            long id,
            String username,
            String passwordHash,
            Status status,
            String displayName) {
        this(id, username, passwordHash, status, displayName, 0);
    }

    public enum Status {
        ACTIVE,
        LOCKED,
        DISABLED
    }
}
