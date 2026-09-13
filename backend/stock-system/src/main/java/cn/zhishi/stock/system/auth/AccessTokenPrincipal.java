package cn.zhishi.stock.system.auth;

import java.time.Instant;
import java.security.Principal;
import java.util.Set;

public record AccessTokenPrincipal(
        long userId,
        String username,
        Set<String> permissions,
        String jti,
        Instant expiresAt) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
