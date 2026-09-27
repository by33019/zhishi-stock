package cn.zhishi.stock.market.domain;

import java.time.LocalDate;

/**
 * 分时序列的请求（STK-06）。
 *
 * @param intervalMinutes 聚合粒度，1/5/15/30/60
 */
public record IntradayRequest(
        String securityId,
        String marketCode,
        LocalDate tradeDate,
        int intervalMinutes) {
}
