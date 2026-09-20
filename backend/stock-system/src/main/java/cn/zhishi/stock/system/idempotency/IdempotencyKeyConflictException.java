package cn.zhishi.stock.system.idempotency;

/** 同一个幂等键被用于不同的请求体（契约 §3.7）。 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("该 Idempotency-Key 已用于不同的请求体");
    }
}
