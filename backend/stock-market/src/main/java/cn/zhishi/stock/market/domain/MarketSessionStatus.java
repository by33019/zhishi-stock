package cn.zhishi.stock.market.domain;

/**
 * 市场交易状态的粗粒度取值。
 *
 * <p>取值刻意保持精简并跨市场稳定（A 股、港股、美股共用同一套），便于前端做视觉与文案分支；
 * 更细的时段阶段见 {@link TradingSession}。
 */
public enum MarketSessionStatus {
    PRE_OPEN,
    CALL_AUCTION,
    TRADING,
    BREAK,
    CLOSED
}
