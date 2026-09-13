package cn.zhishi.stock.system.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class RefreshSessionService {

    private final RefreshSessionStore sessions;
    private final UserAccountRepository accounts;
    private final AccessTokenFactory accessTokens;
    private final OpaqueTokenGenerator refreshTokens;
    private final Clock clock;
    private final Duration refreshTtl;

    public RefreshSessionService(
            RefreshSessionStore sessions,
            UserAccountRepository accounts,
            AccessTokenFactory accessTokens,
            OpaqueTokenGenerator refreshTokens,
            Clock clock,
            Duration refreshTtl) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    public RefreshResult start(UserAccount user, Set<String> permissions) {
        String refreshToken = refreshTokens.next();
        sessions.save(new RefreshTokenRecord(
                RefreshTokenHashing.sha256(refreshToken),
                UUID.randomUUID().toString(),
                user.id(),
                clock.instant().plus(refreshTtl),
                RefreshTokenRecord.Status.ACTIVE));
        AccessToken accessToken = accessTokens.issue(user, permissions);
        return result(accessToken, refreshToken, permissions);
    }

    public RefreshResult rotate(String refreshToken) {
        RefreshTokenRecord current = sessions.findByTokenHash(RefreshTokenHashing.sha256(refreshToken))
                .orElseThrow(this::invalidRefreshToken);
        if (current.status() == RefreshTokenRecord.Status.ROTATED) {
            sessions.revokeFamily(current.familyId());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_REUSED, "检测到刷新令牌重放，会话已撤销");
        }
        Instant now = clock.instant();
        if (current.status() != RefreshTokenRecord.Status.ACTIVE || !current.expiresAt().isAfter(now)) {
            throw invalidRefreshToken();
        }
        UserAccount user = accounts.findById(current.userId())
                .filter(account -> account.status() == UserAccount.Status.ACTIVE)
                .orElseThrow(this::invalidRefreshToken);
        Set<String> permissions = accounts.findPermissions(user.id());
        String nextToken = refreshTokens.next();
        RefreshTokenRecord next = new RefreshTokenRecord(
                RefreshTokenHashing.sha256(nextToken),
                current.familyId(),
                user.id(),
                now.plus(refreshTtl),
                RefreshTokenRecord.Status.ACTIVE);
        sessions.rotate(current, next);
        return result(accessTokens.issue(user, permissions), nextToken, permissions);
    }

    public void revoke(String refreshToken) {
        sessions.findByTokenHash(RefreshTokenHashing.sha256(refreshToken))
                .ifPresent(record -> sessions.revokeFamily(record.familyId()));
    }

    private RefreshResult result(
            AccessToken accessToken, String refreshToken, Set<String> permissions) {
        return new RefreshResult(
                accessToken.value(), refreshToken, accessToken.expiresInSeconds(), permissions);
    }

    private AuthException invalidRefreshToken() {
        return new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN, "刷新令牌无效或已过期");
    }
}
