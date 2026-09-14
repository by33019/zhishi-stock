package cn.zhishi.stock.market.application;

public class MarketDataUnavailableException extends RuntimeException {

    public MarketDataUnavailableException(String marketCode) {
        super("市场 " + marketCode + " 的行情数据暂不可用");
    }

    public MarketDataUnavailableException(String marketCode, Throwable cause) {
        super("市场 " + marketCode + " 的行情数据暂不可用", cause);
    }
}
