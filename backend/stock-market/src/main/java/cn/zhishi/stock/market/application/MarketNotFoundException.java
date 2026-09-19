package cn.zhishi.stock.market.application;

/** 请求的市场代码不受支持。 */
public class MarketNotFoundException extends RuntimeException {

    private final String marketCode;

    public MarketNotFoundException(String marketCode) {
        super("不支持的市场代码：" + marketCode);
        this.marketCode = marketCode;
    }

    public String marketCode() {
        return marketCode;
    }
}
