package cn.zhishi.stock.ai.application;

/**
 * 报告引用查询的参数不合法（契约 §HIS-07）。
 *
 * <p>与 {@code InvalidAiHistoryQueryException} / {@code InvalidAiContextQueryException} 同形：
 * 业务码由异常自身携带，{@code GlobalExceptionHandler} 不靠 {@code instanceof} 推断。
 *
 * <p>业务码用通用的 {@code INVALID_REQUEST}：契约 §13.5 / §14.2 只为"目标"定义了
 * {@code AI_TARGET_INVALID}，为"证据类型不在白名单"新造一个 {@code AI_} 前缀的码
 * 会让前端不得不认识一个契约里没有的取值（同 {@code InvalidAiContextQueryException} 的处置）。
 *
 * <p>实际只有一种触发：{@code evidenceType} 不是 {@code AiEvidenceType} 的取值。
 * 单独一个类而不是复用 AI-02 的那个，是因为提示语要落在**证据**上——
 * "不支持的证据类型 NEWW"比"上下文参数不合法"更能让调用方自己发现拼写错误。
 */
public class InvalidAiEvidenceQueryException extends RuntimeException {

    private static final String CODE = "INVALID_REQUEST";

    public InvalidAiEvidenceQueryException(String message) {
        super(message);
    }

    public String code() {
        return CODE;
    }
}
