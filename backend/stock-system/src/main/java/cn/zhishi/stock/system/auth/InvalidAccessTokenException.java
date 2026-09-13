package cn.zhishi.stock.system.auth;

public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException(Throwable cause) {
        super("Access Token 无效或已过期", cause);
    }
}
