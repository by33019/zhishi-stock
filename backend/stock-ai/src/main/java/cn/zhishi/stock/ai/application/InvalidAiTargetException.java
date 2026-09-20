package cn.zhishi.stock.ai.application;

/**
 * AI 目标不合法（→ 400，业务码 {@code AI_TARGET_INVALID}，契约 §13.5）。
 *
 * <p>覆盖契约「场景目标规则」的全部违反形态：类型不在场景允许集合内、
 * 数量超出区间、主目标不是恰好一个、客户端传了服务端专用的 {@code CONTEXT} 角色、
 * 同一目标重复出现、目标标识解析不到真实主数据。
 *
 * <p>为什么"目标不存在"在这里是 400 而不是 404：AI-02 的目标是**请求体**的一部分，
 * 不是路径资源。契约 §13.5 把这类问题统一归到 {@code AI_TARGET_INVALID}，
 * 前端据此就地提示用户改选标的，而不是跳转到一个"找不到"的页面。
 */
public class InvalidAiTargetException extends RuntimeException {

    private final String code;

    private InvalidAiTargetException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InvalidAiTargetException invalid(String message) {
        return new InvalidAiTargetException("AI_TARGET_INVALID", message);
    }
}
