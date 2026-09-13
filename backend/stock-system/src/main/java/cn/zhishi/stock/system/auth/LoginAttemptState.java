package cn.zhishi.stock.system.auth;

import java.time.Instant;

public record LoginAttemptState(int failures, Instant lockedUntil) {

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
