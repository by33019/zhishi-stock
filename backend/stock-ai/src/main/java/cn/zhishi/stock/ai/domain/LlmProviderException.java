package cn.zhishi.stock.ai.domain;

/**
 * LLM 调用失败。
 *
 * <p>携带 {@link LlmErrorCategory} 而不是让调用方解析消息文本：错误类别决定重试策略，
 * 而"从 message 里找关键词"在供应商改一次措辞后就会静默失效——失效的表现是
 * "不再重试"或"无限重试"，两者都不会在测试里变红。
 *
 * <p>{@code message} 必须是**可审计的脱敏摘要**（与 {@code ai_task.error_message} 同要求）：
 * 不得包含 prompt 正文、密钥或用户信息。
 */
public class LlmProviderException extends RuntimeException {

    private final LlmErrorCategory category;
    private final boolean retryable;
    private final String errorCode;

    public LlmProviderException(
            LlmErrorCategory category, String errorCode, String message, boolean retryable) {
        super(message);
        this.category = category;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public LlmProviderException(
            LlmErrorCategory category, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.errorCode = errorCode;
        this.retryable = category.retryable();
    }

    public LlmErrorCategory category() {
        return category;
    }

    public String errorCode() {
        return errorCode;
    }

    /** 是否值得重试。默认取类别自带的判定，可被显式覆盖。 */
    public boolean retryable() {
        return retryable;
    }

    public static LlmProviderException timeout(String message) {
        return new LlmProviderException(LlmErrorCategory.TIMEOUT, "AI_TASK_TIMED_OUT", message, true);
    }

    public static LlmProviderException rateLimited(String message) {
        return new LlmProviderException(
                LlmErrorCategory.RATE_LIMIT, "AI_PROVIDER_RATE_LIMITED", message, true);
    }

    public static LlmProviderException providerUnavailable(String message) {
        return new LlmProviderException(
                LlmErrorCategory.PROVIDER, "AI_PROVIDER_UNAVAILABLE", message, true);
    }

    public static LlmProviderException safetyRejected(String message) {
        return new LlmProviderException(LlmErrorCategory.SAFETY, "AI_OUTPUT_REJECTED", message, false);
    }

    public static LlmProviderException dataInvalid(String message) {
        return new LlmProviderException(LlmErrorCategory.DATA, "AI_CORE_DATA_MISSING", message, false);
    }
}
