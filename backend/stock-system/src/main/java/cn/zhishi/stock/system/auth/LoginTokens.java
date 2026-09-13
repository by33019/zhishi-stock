package cn.zhishi.stock.system.auth;

import java.util.Set;

public record LoginTokens(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        Set<String> permissions,
        UserAccount user) {

    public LoginTokens(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            Set<String> permissions) {
        this(accessToken, refreshToken, expiresInSeconds, permissions, null);
    }
}
