package cn.zhishi.stock.ai.application;

/**
 * 反馈请求不合法（契约 §HIS-08）。
 *
 * <p>与 {@code InvalidAiTargetException} / {@code InvalidAiContextQueryException} 同形：
 * 业务码由异常自身携带，{@code GlobalExceptionHandler} 不靠 instanceof 推断。
 *
 * <p>业务码用通用的 {@code INVALID_REQUEST} 而不是自造一个 {@code AI_FEEDBACK_INVALID}：
 * 契约 §HIS-08 没有为反馈定义专属业务码，而"参数取值不在白名单"与其它接口
 * 报的是同一件事。自造码会让前端为同一个语义多写一条分支。
 */
public class InvalidAiFeedbackException extends RuntimeException {

    private static final String CODE = "INVALID_REQUEST";

    public InvalidAiFeedbackException(String message) {
        super(message);
    }

    public String code() {
        return CODE;
    }
}
