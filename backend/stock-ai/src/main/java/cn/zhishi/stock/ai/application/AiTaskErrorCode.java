package cn.zhishi.stock.ai.application;

/**
 * AI 任务域的业务码与 HTTP 状态。
 *
 * <p>状态码写在这里而不是 {@code GlobalExceptionHandler} 里：同一个模块的异常状态各不相同
 * （404 / 409 / 429 / 503），在 handler 里用 switch 推断会让"新增一个业务码"必须同时改两处。
 *
 * <p>用 {@code int} 而不是 {@code HttpStatus}，是为了让 {@code stock-ai} 不依赖 spring-web
 * （同 {@code WatchlistErrorCode}）。
 *
 * <p>两个"状态不允许"的码刻意分开：{@code AI_TASK_NOT_CANCELABLE} 与
 * {@code AI_TASK_NOT_RETRYABLE} 的 HTTP 状态相同，但前端要给出的提示不同
 * （"这个任务已经结束了" vs "只有失败的任务能重试"）。合并成一个会让提示变成一句含糊的话。
 */
public enum AiTaskErrorCode {

    /** 任务不存在，或不属于当前用户（为防水平越权，两种情况不区分）。 */
    TASK_NOT_FOUND("AI_TASK_NOT_FOUND", 404),
    /** 该状态不接受取消意图之外的变更（如对已完成任务要求重试）。 */
    TASK_NOT_CANCELABLE("AI_TASK_NOT_CANCELABLE", 409),
    /** 仅 {@code FAILED} / {@code TIMED_OUT} 可重试。 */
    TASK_NOT_RETRYABLE("AI_TASK_NOT_RETRYABLE", 409),
    /** 单用户并发任务数已达上限（契约 §13.5）。 */
    CONCURRENCY_EXCEEDED("AI_CONCURRENCY_EXCEEDED", 429),
    /** 每日额度已用完（契约 §13.5）。响应体带 {@code dailyLimit} / {@code usedCount} / {@code resetsAt}。 */
    QUOTA_EXCEEDED("AI_QUOTA_EXCEEDED", 429),
    /** 核心行情缺失：创建任务时必须拒绝（契约 §13.5「核心行情缺失时拒绝创建或终止任务」）。 */
    CORE_DATA_MISSING("AI_CORE_DATA_MISSING", 503),
    /** 会话不存在，或不属于当前用户。 */
    SESSION_NOT_FOUND("AI_SESSION_NOT_FOUND", 404),
    /**
     * 报告不存在，或不属于当前用户。
     *
     * <p>也与"任务失败因此没有报告资源"共用同一码：契约 §HIS-06 明写"失败任务不存在报告资源"。
     * 为失败任务单独给一个码，等于告诉调用方"这个任务存在但失败了"——
     * 而调用方本来只需知道"拿不到报告"。
     */
    REPORT_NOT_FOUND("AI_REPORT_NOT_FOUND", 404),
    /** 会话已不是 {@code ACTIVE}（追问要求活动会话）。 */
    SESSION_READ_ONLY("AI_SESSION_READ_ONLY", 409);

    private final String externalCode;
    private final int httpStatus;

    AiTaskErrorCode(String externalCode, int httpStatus) {
        this.externalCode = externalCode;
        this.httpStatus = httpStatus;
    }

    public String externalCode() {
        return externalCode;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
