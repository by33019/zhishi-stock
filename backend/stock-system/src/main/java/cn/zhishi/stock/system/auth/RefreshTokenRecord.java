package cn.zhishi.stock.system.auth;

import java.time.Instant;

public record RefreshTokenRecord(
        String tokenHash,
        String familyId,
        long userId,
        Instant expiresAt,
        Status status) {

    public RefreshTokenRecord withStatus(Status newStatus) {
        return new RefreshTokenRecord(tokenHash, familyId, userId, expiresAt, newStatus);
    }

    public enum Status {
        ACTIVE,
        ROTATED,
        REVOKED
    }
}
