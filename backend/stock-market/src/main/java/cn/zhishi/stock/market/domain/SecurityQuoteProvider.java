package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 个股行情来源端口。
 *
 * <p>广度计数从一整批个股行情取数，因此"不混用不同批次"是结构性保证：
 * 一次调用返回的就是同一批次。
 */
@FunctionalInterface
public interface SecurityQuoteProvider {

    /** 返回指定市场、指定交易日的一整批个股行情；市场不受支持时返回空列表。 */
    List<SecurityQuote> fetchUniverse(String marketCode, LocalDate tradeDate);
}
