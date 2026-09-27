package cn.zhishi.stock.market.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 分时序列（STK-06 的响应主体）。
 *
 * @param intervalMinutes 聚合粒度（1/5/15/30/60 分钟），请求参数的原样回显
 */
public record IntradaySeries(
        SecuritySummary security,
        LocalDate tradeDate,
        int intervalMinutes,
        String previousClosePrice,
        OffsetDateTime dataCutoffAt,
        MarketOverview.DataStatus dataStatus,
        List<IntradayPoint> points) {

    public IntradaySeries {
        points = List.copyOf(points);
    }
}
