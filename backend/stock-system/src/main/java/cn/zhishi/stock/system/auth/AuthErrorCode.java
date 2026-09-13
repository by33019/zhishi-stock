package cn.zhishi.stock.system.auth;

public enum AuthErrorCode {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    INVALID_REFRESH_TOKEN,
    REFRESH_TOKEN_REUSED
}
