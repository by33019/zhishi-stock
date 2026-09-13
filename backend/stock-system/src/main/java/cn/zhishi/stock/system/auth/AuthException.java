package cn.zhishi.stock.system.auth;

public class AuthException extends RuntimeException {

    private final AuthErrorCode code;

    public AuthException(AuthErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AuthErrorCode code() {
        return code;
    }
}
