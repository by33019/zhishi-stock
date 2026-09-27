package cn.zhishi.stock.system.auth;

import java.util.Optional;

public interface RefreshSessionStore {

    enum RotationOutcome {
        SUCCESS,
        REUSED,
        INVALID,
        /** 令牌里的版本号已落后于用户当前版本：会话被管理员强制终止过。 */
        STALE_VERSION
    }

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);

    void save(RefreshTokenRecord record);

    RotationOutcome rotate(RefreshTokenRecord previous, RefreshTokenRecord next);

    void revokeFamily(String familyId);

    /**
     * 撤销某个用户的**全部**刷新会话（契约 §16.1 ADM-USR-05/06/08/09：强制下线）。
     *
     * <p>只能靠"用户 → 会话族"的反向索引做到：刷新令牌本身是随机的，
     * 从 userId 推不出 tokenHash。没有索引时"强制下线"就退化成"删掉一个不知道在哪的键"，
     * 而它不会有任何报错——只会静默地什么都没撤销。
     *
     * @return 被置为 {@code REVOKED} 的刷新令牌条数（契约里的 {@code revokedSessionCount}）
     */
    int revokeAllForUser(long userId);
}
