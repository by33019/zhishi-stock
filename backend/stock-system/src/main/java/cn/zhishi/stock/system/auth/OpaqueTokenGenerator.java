package cn.zhishi.stock.system.auth;

@FunctionalInterface
public interface OpaqueTokenGenerator {

    String next();
}
