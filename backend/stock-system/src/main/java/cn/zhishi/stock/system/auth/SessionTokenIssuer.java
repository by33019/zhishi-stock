package cn.zhishi.stock.system.auth;

import java.util.Set;

public class SessionTokenIssuer implements TokenIssuer {

    private final RefreshSessionService sessions;

    public SessionTokenIssuer(RefreshSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public LoginTokens issue(UserAccount user, Set<String> permissions) {
        RefreshResult result = sessions.start(user, permissions);
        return new LoginTokens(
                result.accessToken(),
                result.refreshToken(),
                result.expiresInSeconds(),
                result.permissions(),
                user);
    }
}
