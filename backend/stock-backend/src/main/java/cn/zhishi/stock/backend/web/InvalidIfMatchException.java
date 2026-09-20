package cn.zhishi.stock.backend.web;

/** {@code If-Match} 缺失或格式非法（契约 §3.7 的乐观锁前置条件）。 */
public class InvalidIfMatchException extends RuntimeException {

    public InvalidIfMatchException(String message) {
        super(message);
    }
}
