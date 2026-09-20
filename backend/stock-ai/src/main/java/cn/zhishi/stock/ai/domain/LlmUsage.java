package cn.zhishi.stock.ai.domain;

/**
 * 一次调用的 token 用量，字段与 {@code ai_usage} 的 token 列对齐。
 *
 * <p>不变量 {@code total >= prompt + completion} 与 {@code ck_ai_usage_tokens} 同口径。
 * {@code cachedTokens} 单列而不是并入 {@code promptTokens}：缓存命中的部分成本不同，
 * 合并后无法回答"缓存有没有起作用"。
 */
public record LlmUsage(int promptTokens, int completionTokens, int cachedTokens, int totalTokens) {

    public static final LlmUsage EMPTY = new LlmUsage(0, 0, 0, 0);

    public LlmUsage {
        if (promptTokens < 0 || completionTokens < 0 || cachedTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token 计数不得为负");
        }
        if (totalTokens < promptTokens + completionTokens) {
            throw new IllegalArgumentException(
                    "totalTokens 不得小于 promptTokens + completionTokens："
                            + totalTokens + " < " + (promptTokens + completionTokens));
        }
    }
}
