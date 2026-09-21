package cn.zhishi.stock.ai.application;

/**
 * 当日额度已用完。
 *
 * <p>单独一个子类而不是往 {@link AiTaskException} 里塞一个可空的 {@code data} 字段：
 * 契约 §26.4 明确要求这个响应体带 {@code dailyLimit} / {@code usedCount} / {@code resetsAt}，
 * 而其它业务码的 {@code data} 是 {@code null}。用一个"有时有、有时没有"的字段，
 * 会让"这个码到底该不该带数据"变成一个只能靠读 handler 才能确认的问题。
 */
public class AiQuotaExceededException extends AiTaskException {

    private final AiTaskQuota quota;

    public AiQuotaExceededException(AiTaskQuota quota) {
        super(AiTaskErrorCode.QUOTA_EXCEEDED,
                "今日 AI 分析额度已用完（" + quota.usedCount() + "/" + quota.dailyLimit() + "）");
        this.quota = quota;
    }

    public AiTaskQuota quota() {
        return quota;
    }
}
