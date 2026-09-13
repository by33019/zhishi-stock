package cn.zhishi.stock.system.auth;

import java.util.Set;

public record RefreshResult(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        Set<String> permissions) {
}
