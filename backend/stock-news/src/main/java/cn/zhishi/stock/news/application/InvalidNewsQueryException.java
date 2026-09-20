package cn.zhishi.stock.news.application;

/**
 * 资讯查询参数非法（→ 400 {@code INVALID_REQUEST}）。
 *
 * <p>枚举绑定失败不能交给 Spring 的 {@code MethodArgumentTypeMismatchException}：
 * 那会把"取值不在白名单"混同为"参数格式错误"（同 MKT-04 / STK-01 的处理）。
 * 因此 controller 一律把参数声明为 {@code String}，在用例层校验并抛本异常。
 */
public class InvalidNewsQueryException extends RuntimeException {

    public InvalidNewsQueryException(String message) {
        super(message);
    }
}
