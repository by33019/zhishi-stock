package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * STK-07 响应体：单只证券的一段 K 线。
 *
 * <p>{@code dataCutoffAt} 恒等于最后一个点位的时刻——K 线的可信边界就是它最后一个点，
 * 不另立一个可能与之矛盾的时间（同 M2-03 的 {@code TurnoverTrend}）。
 *
 * <p>{@code period} / {@code adjustment} 回显**实际生效值**：调用方据此确认服务端
 * 没有静默替换参数。
 */
public record KlineSeries(
        SecuritySummary security,
        KlinePeriod period,
        KlineAdjustment adjustment,
        OffsetDateTime dataCutoffAt,
        MarketOverview.DataStatus dataStatus,
        List<KlinePoint> points) {

    public KlineSeries {
        points = List.copyOf(points);
    }
}
