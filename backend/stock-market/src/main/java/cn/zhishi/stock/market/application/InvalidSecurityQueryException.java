package cn.zhishi.stock.market.application;

/**
 * 证券查询参数非法。
 *
 * <p>对应 HTTP 400 / 业务码 {@code INVALID_REQUEST}。
 * 只用于**参数本身不合法**（长度、范围、白名单）；
 * 筛选值不存在于数据中不属于此类——那是"没有数据"，应返回空结果而不是报错。
 */
public class InvalidSecurityQueryException extends RuntimeException {

    public InvalidSecurityQueryException(String message) {
        super(message);
    }
}
