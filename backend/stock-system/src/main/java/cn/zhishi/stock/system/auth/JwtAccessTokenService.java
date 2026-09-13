package cn.zhishi.stock.system.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;

public class JwtAccessTokenService implements AccessTokenFactory {

    private final SecretKey key;
    private final Clock clock;
    private final Duration timeToLive;

    public JwtAccessTokenService(String secret, Clock clock, Duration timeToLive) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
        this.timeToLive = timeToLive;
    }

    @Override
    public AccessToken issue(UserAccount user, Set<String> permissions) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(timeToLive);
        String jti = UUID.randomUUID().toString();
        String value = Jwts.builder()
                .subject(Long.toString(user.id()))
                .claim("username", user.username())
                .claim("permissions", List.copyOf(permissions))
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new AccessToken(value, jti, timeToLive.toSeconds());
    }

    public AccessTokenPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            List<?> values = claims.get("permissions", List.class);
            Set<String> permissions = new LinkedHashSet<>();
            if (values != null) {
                values.forEach(value -> permissions.add(value.toString()));
            }
            return new AccessTokenPrincipal(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    Set.copyOf(permissions),
                    claims.getId(),
                    claims.getExpiration().toInstant());
        } catch (RuntimeException exception) {
            throw new InvalidAccessTokenException(exception);
        }
    }
}
