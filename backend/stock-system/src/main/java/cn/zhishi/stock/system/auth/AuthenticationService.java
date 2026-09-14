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
    private final String dummyPasswordHash;

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
        this.dummyPasswordHash = passwordEncoder.encode("unknown-account-timing-protection");
    }

    public LoginTokens login(String username, String password) {
        UserAccount user = accounts.findByUsername(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw invalidCredentials();
        }
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
            recordFailure(user.id(), now);
        }

        attempts.clear(user.id());
        Set<String> permissions = accounts.findPermissions(user.id());
        return tokenIssuer.issue(user, permissions);
    }

    private void recordFailure(long userId, Instant now) {
        if (attempts.recordFailure(userId, now, maximumFailures, lockDuration)) {
            throw new AuthException(AuthErrorCode.ACCOUNT_LOCKED, "登录失败次数过多，请稍后重试");
        }
        throw invalidCredentials();
    }

    private AuthException invalidCredentials() {
        return new AuthException(AuthErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
    }
}
