package cn.zhishi.stock.system.auth;

import java.security.SecureRandom;
import java.util.Base64;

public class SecureOpaqueTokenGenerator implements OpaqueTokenGenerator {

    private static final int TOKEN_BYTES = 48;
    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
