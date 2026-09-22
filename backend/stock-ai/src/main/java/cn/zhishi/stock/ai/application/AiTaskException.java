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

    /**
     * 报告不存在、不属于当前用户，或该任务失败因而没有报告资源。
     *
     * <p>三种情况共用同一句话与同一个业务码：只要让"存在但无权查看"与"不存在"
     * 在响应上可区分，就等于提供了一个探测他人报告 ID 是否有效的接口（契约 §23.1）。
     */
    public static AiTaskException reportNotFound(long reportId) {
        return new AiTaskException(AiTaskErrorCode.REPORT_NOT_FOUND, "AI 报告不存在：" + reportId);
    }

    /**
     * 会话版本冲突（{@code If-Match} 已过期）。
     *
     * <p>文案里带上**期望版本与当前版本**：前端拿着它就知道该重新拉一次，
     * 而只回一句"冲突了"会让人不知道该做什么。
     */
    public static AiTaskException sessionVersionConflict(long sessionId, int expected, int actual) {
        return new AiTaskException(
                AiTaskErrorCode.SESSION_VERSION_CONFLICT,
                "会话已被修改，请重新获取后再提交：sessionId=" + sessionId
                        + " 期望版本=" + expected + " 当前版本=" + actual);
    }
}
