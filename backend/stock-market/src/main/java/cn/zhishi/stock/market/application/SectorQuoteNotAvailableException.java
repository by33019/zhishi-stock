package cn.zhishi.stock.market.application;

/**
 * 板块没有可统计的行情（→ 503 / {@code SECTOR_QUOTE_NOT_AVAILABLE}）。
 *
 * <p>用于 SEC-04：板块存在且启用，但没有任何成分股带有效行情（成分全停牌，或成分关系为空）。
 *
 * <p>为什么返回 503 而不是 200 带一堆 {@code null}：该接口的契约就是"获取板块最新行情统计"，
 * 没有任何统计可言时返回 200 会让调用方以为拿到了数据。这与
 * {@link MarketDataUnavailableException} 的处置一致——数据不可用是服务端状态，不是客户端参数问题。
 */
public class SectorQuoteNotAvailableException extends RuntimeException {

    public SectorQuoteNotAvailableException(String sectorId) {
        super("板块 " + sectorId + " 暂无可统计的行情数据");
    }
}
