package cn.zhishi.stock.market.application;

/**
 * K 线查询参数非法（→ 400）。
 *
 * <p>三种情况的 HTTP 状态都是 400，只有业务码不同，因此合并成一个异常类、
 * 用静态工厂区分语义。拆成三个异常类只会让异常处理器里出现三段几乎相同的代码。
 * 业务码显式携带在异常上，不在处理器里靠 {@code instanceof} 推断。
 */
public class InvalidKlineParameterException extends RuntimeException {

    private final String code;

    private InvalidKlineParameterException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 参数取值不在白名单、日期格式非法、起止日期颠倒。 */
    public static InvalidKlineParameterException invalid(String message) {
        return new InvalidKlineParameterException("INVALID_REQUEST", message);
    }

    /** 查询跨度超出该周期上限（§8.3：日 K 最多 10 年，周/月 K 最多 20 年）。 */
    public static InvalidKlineParameterException rangeTooLarge(String message) {
        return new InvalidKlineParameterException("KLINE_RANGE_TOO_LARGE", message);
    }

    /**
     * 请求了不支持的复权方式。
     *
     * <p>必须明确报错而非静默替换成 {@code NONE}——§8.3 明确要求"不支持的复权方式
     * 返回明确错误而非静默替换"。
     */
    public static InvalidKlineParameterException adjustmentNotSupported(String message) {
        return new InvalidKlineParameterException("ADJUSTMENT_NOT_SUPPORTED", message);
    }
}
