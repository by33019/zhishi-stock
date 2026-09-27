package cn.zhishi.stock.system.auth;

import java.util.Optional;
import java.util.Set;

public interface UserAccountRepository {

    Optional<UserAccount> findByUsername(String username);

    default Optional<UserAccount> findById(long userId) {
        return Optional.empty();
    }

    /** 按邮箱找未删除账号（AUTH-07 密码重置兑换的入口）。 */
    default Optional<UserAccount> findByEmail(String email) {
        return Optional.empty();
    }

    /**
     * 更新密码并递增 {@code token_version}（AUTH-07：重置成功即全员下线）。
     * {@code version} 同步 +1，与 {@code AdminUserMapper.bumpTokenVersion} 同一口径——
     * 会话被撤销本身就是资源状态变化，不让 If-Match 拿着旧版本号再改一次。
     */
    default void updatePasswordHashBumpingTokenVersion(long userId, String newPasswordHash) {
        throw new UnsupportedOperationException("此仓储实现不支持密码更新");
    }

    Set<String> findPermissions(long userId);
}
