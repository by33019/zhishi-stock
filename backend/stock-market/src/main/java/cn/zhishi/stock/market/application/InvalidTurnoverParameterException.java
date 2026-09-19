package cn.zhishi.stock.market.application;

/**
 * MKT-04 的请求参数不合法（未知的 {@code range}、不支持的 {@code interval}、
 * 或对日维度档位传了 {@code interval}）。
 *
 * <p>映射为 HTTP 400 与业务码 {@code INVALID_REQUEST}。
 */
public class InvalidTurnoverParameterException extends RuntimeException {

    public InvalidTurnoverParameterException(String message) {
        super(message);
    }
}
