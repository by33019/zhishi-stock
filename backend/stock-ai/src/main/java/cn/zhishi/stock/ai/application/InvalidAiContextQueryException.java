package cn.zhishi.stock.ai.application;

/**
 * AI 上下文预览请求参数不合法（→ 400，业务码 {@code INVALID_REQUEST}）。
 *
 * <p>与 {@link InvalidAiTargetException} 分开：目标问题是"选错了对象"，
 * 参数问题是"给错了范围或场景"，前端需要给出不同的就地提示。
 *
 * <p>业务码**不**用 {@code AI_TARGET_INVALID}：契约 §13.5 只为"目标"定义了这个码，
 * 把"区间跨度超限"也塞进它，会让前端把两种完全不同的提示混为一谈。
 * 也不用新造的 {@code AI_} 前缀码——契约的码表里没有它。
 */
public class InvalidAiContextQueryException extends RuntimeException {

    /** 与项目其它参数类异常一致的业务码。 */
    private static final String CODE = "INVALID_REQUEST";

    private final String code;

    public InvalidAiContextQueryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InvalidAiContextQueryException invalid(String message) {
        return new InvalidAiContextQueryException(CODE, message);
    }

    /** 场景码不认识。 */
    public static InvalidAiContextQueryException unknownScene(String scene) {
        return new InvalidAiContextQueryException(CODE, "不支持的场景：" + scene);
    }

    /** 分析区间不合法。 */
    public static InvalidAiContextQueryException invalidRange(String message) {
        return new InvalidAiContextQueryException(CODE, message);
    }
}
