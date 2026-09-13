package cn.zhishi.stock.system.auth;

import java.util.Optional;

public interface RefreshSessionStore {

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);

    void save(RefreshTokenRecord record);

    void rotate(RefreshTokenRecord previous, RefreshTokenRecord next);

    void revokeFamily(String familyId);
}
