package cn.zhishi.stock.system.auth;

import java.util.Optional;
import java.util.Set;

public interface UserAccountRepository {

    Optional<UserAccount> findByUsername(String username);

    default Optional<UserAccount> findById(long userId) {
        return Optional.empty();
    }

    Set<String> findPermissions(long userId);
}
