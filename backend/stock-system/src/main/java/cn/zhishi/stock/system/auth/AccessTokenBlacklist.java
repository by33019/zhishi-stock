package cn.zhishi.stock.system.auth;

import java.time.Instant;

public interface AccessTokenBlacklist {

    boolean contains(String jti);

    void add(String jti, Instant expiresAt);
}
