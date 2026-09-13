package cn.zhishi.stock.system.auth;

import java.util.Set;

@FunctionalInterface
public interface TokenIssuer {

    LoginTokens issue(UserAccount user, Set<String> permissions);
}
