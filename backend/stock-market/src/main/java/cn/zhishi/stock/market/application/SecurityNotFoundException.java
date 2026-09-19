package cn.zhishi.stock.market.application;

/** 证券不存在（→ 404 {@code SECURITY_NOT_FOUND}）。 */
public class SecurityNotFoundException extends RuntimeException {

    private final String securityId;

    public SecurityNotFoundException(String securityId) {
        super("证券不存在：" + securityId);
        this.securityId = securityId;
    }

    public String securityId() {
        return securityId;
    }
}
