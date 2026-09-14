package cn.zhishi.stock.system.auth;

import java.util.Optional;
import java.time.Duration;
import java.time.Instant;

public interface LoginAttemptStore {

    Optional<LoginAttemptState> find(long userId);

    void save(long userId, LoginAttemptState state);

    boolean recordFailure(
            long userId,
            Instant now,
            int maximumFailures,
            Duration lockDuration);

    void clear(long userId);
}
