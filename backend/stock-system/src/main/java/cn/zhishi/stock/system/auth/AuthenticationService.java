package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;

public class AuthenticationService {

    private final UserAccountRepository accounts;
    private final LoginAttemptStore attempts;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final Clock clock;
    private final int maximumFailures;
    private final Duration lockDuration;

    public AuthenticationService(
            UserAccountRepository accounts,
            LoginAttemptStore attempts,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer,
            Clock clock,
            int maximumFailures,
            Duration lockDuration) {
        this.accounts = accounts;
        this.attempts = attempts;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.clock = clock;
        this.maximumFailures = maximumFailures;
        this.lockDuration = lockDuration;
    }

    public LoginTokens login(String username, String password) {
        UserAccount user = accounts.findByUsername(username)
                .orElseThrow(this::invalidCredentials);
        if (user.status() == UserAccount.Status.DISABLED) {
            throw new AuthException(AuthErrorCode.ACCOUNT_DISABLED, "账户已停用");
        }
        if (user.status() == UserAccount.Status.LOCKED) {
            throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED, "账户已锁定");
        }

        Instant now = clock.instant();
        LoginAttemptState current = attempts.find(user.id()).orElse(new LoginAttemptState(0, null));
        if (current.isLocked(now)) {
            throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED, "登录失败次数过多，请稍后重试");
        }
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            recordFailure(user.id(), current, now);
        }

        attempts.clear(user.id());
        Set<String> permissions = accounts.findPermissions(user.id());
        return tokenIssuer.issue(user, permissions);
    }

    private void recordFailure(long userId, LoginAttemptState current, Instant now) {
        int failures = current.lockedUntil() != null && !current.isLocked(now)
                ? 1
                : current.failures() + 1;
        if (failures >= maximumFailures) {
            attempts.save(userId, new LoginAttemptState(failures, now.plus(lockDuration)));
            throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED, "登录失败次数过多，请稍后重试");
        }
        attempts.save(userId, new LoginAttemptState(failures, null));
        throw invalidCredentials();
    }

    private AuthException invalidCredentials() {
        return new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
    }
}
