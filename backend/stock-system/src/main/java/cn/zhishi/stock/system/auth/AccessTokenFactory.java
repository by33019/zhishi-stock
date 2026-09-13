package cn.zhishi.stock.system.auth;

import java.util.Set;

@FunctionalInterface
public interface AccessTokenFactory {

    AccessToken issue(UserAccount user, Set<String> permissions);
}
