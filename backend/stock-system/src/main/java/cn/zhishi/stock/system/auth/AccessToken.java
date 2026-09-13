package cn.zhishi.stock.system.auth;

public record AccessToken(String value, String jti, long expiresInSeconds) {
}
