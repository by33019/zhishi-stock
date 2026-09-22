package cn.zhishi.stock.ai.application;

/**
 * 会话历史查询的参数不合法（契约 §HIS-01 / §HIS-05）。
 *
 * <p>与 {@code InvalidAiTargetException} / {@code InvalidAiFeedbackException} 同形：
 * 业务码由异常自身携带，{@code GlobalExceptionHandler} 不靠 instanceof 推断。
 *
 * <p>业务码用通用的 {@code INVALID_REQUEST}，与排行榜 / 证券列表对"分页越界"的处置一致
 * ——契约 §HIS-01 没有为历史列表定义专属业务码。
 */
public class InvalidAiHistoryQueryException extends RuntimeException {

    private static final String CODE = "INVALID_REQUEST";

    public InvalidAiHistoryQueryException(String message) {
        super(message);
    }

    public String code() {
        return CODE;
    }
}
