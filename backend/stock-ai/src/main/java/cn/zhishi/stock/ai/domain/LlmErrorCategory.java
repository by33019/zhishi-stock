package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * LLM 调用的错误类别，取值与 {@code ai_task.error_category} /
 * {@code ai_usage.error_category} 的 CHECK 约束同集合。
 *
 * <p>分类的价值在于决定**要不要重试**：{@link #RATE_LIMIT} 与 {@link #TIMEOUT} 值得退避重试，
 * {@link #SAFETY} 与 {@link #DATA} 重试多少次都是同样结果。把"可重试性"写在类别旁边
 * （见 {@link #retryable()}），比让每个消费方各自判断更不容易分叉。
 */
public enum LlmErrorCategory {

    /** 超时。可重试。 */
    TIMEOUT(true),

    /** 被供应商限流。可重试（需退避）。 */
    RATE_LIMIT(true),

    /** 输入数据问题（如上下文为空、格式非法）。重试无用。 */
    DATA(false),

    /** 安全拒绝（命中禁用表达或提示注入）。重试无用，且重试可能造成伤害。 */
    SAFETY(false),

    /** 供应商侧故障（5xx、连接失败）。可重试。 */
    PROVIDER(true),

    /** 本侧系统故障（序列化失败、装配缺失）。重试无用——先修代码。 */
    SYSTEM(false);

    private final boolean retryable;

    LlmErrorCategory(boolean retryable) {
        this.retryable = retryable;
    }

    /** 该类别是否值得自动重试。 */
    public boolean retryable() {
        return retryable;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
