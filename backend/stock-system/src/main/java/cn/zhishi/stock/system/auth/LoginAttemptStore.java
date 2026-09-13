package cn.zhishi.stock.system.auth;

import java.util.Optional;

public interface LoginAttemptStore {

    Optional<LoginAttemptState> find(long userId);

    void save(long userId, LoginAttemptState state);

    void clear(long userId);
}
