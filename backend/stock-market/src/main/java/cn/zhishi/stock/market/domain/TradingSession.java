package cn.zhishi.stock.market.domain;

/**
 * 交易时段的细粒度阶段。
 *
 * <p>每个阶段归属一个粗粒度的 {@link MarketSessionStatus}，映射关系只在此处维护一处，
 * 避免出现"状态与阶段各自定义、互相漂移"的情况。
 */
public enum TradingSession {

    PRE_OPEN(MarketSessionStatus.PRE_OPEN),
    OPENING_CALL_AUCTION(MarketSessionStatus.CALL_AUCTION),
    MORNING_CONTINUOUS(MarketSessionStatus.TRADING),
    LUNCH_BREAK(MarketSessionStatus.BREAK),
    AFTERNOON_CONTINUOUS(MarketSessionStatus.TRADING),
    CLOSING_CALL_AUCTION(MarketSessionStatus.CALL_AUCTION),
    CLOSED(MarketSessionStatus.CLOSED);

    private final MarketSessionStatus status;

    TradingSession(MarketSessionStatus status) {
        this.status = status;
    }

    public MarketSessionStatus status() {
        return status;
    }
}
