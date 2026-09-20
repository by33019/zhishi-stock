package cn.zhishi.stock.system.idempotency;

/** 契约要求带 {@code Idempotency-Key} 的接口没有带这个头。 */
public class IdempotencyKeyMissingException extends RuntimeException {

    public IdempotencyKeyMissingException() {
        super("缺少 Idempotency-Key 请求头");
    }
}
