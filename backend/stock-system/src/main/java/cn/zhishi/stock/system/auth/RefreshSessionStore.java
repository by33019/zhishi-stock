package cn.zhishi.stock.system.auth;

import java.util.Optional;

public interface RefreshSessionStore {

    enum RotationOutcome {
        SUCCESS,
        REUSED,
        INVALID
    }

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);

    void save(RefreshTokenRecord record);

    RotationOutcome rotate(RefreshTokenRecord previous, RefreshTokenRecord next);

    void revokeFamily(String familyId);
}
