package cn.zhishi.stock.ai.application;

/**
 * AI 任务域的业务异常，携带自己的业务码与 HTTP 状态。
 *
 * <p>与 {@code WatchlistException} 同样的写法：业务码由异常自身携带，
 * {@code GlobalExceptionHandler} 不靠 instanceof 推断。
 */
public class AiTaskException extends RuntimeException {

    private final AiTaskErrorCode code;

    public AiTaskException(AiTaskErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AiTaskErrorCode code() {
        return code;
    }

    /** 任务不存在或不属于当前用户。两种情况的文案相同——不能泄露他人资源的存在性。 */
    public static AiTaskException taskNotFound(long taskId) {
        return new AiTaskException(AiTaskErrorCode.TASK_NOT_FOUND, "AI 任务不存在：" + taskId);
    }

    public static AiTaskException sessionNotFound(long sessionId) {
        return new AiTaskException(AiTaskErrorCode.SESSION_NOT_FOUND, "AI 会话不存在：" + sessionId);
    }
}
