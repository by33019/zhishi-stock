package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.MarketBreadth;
import cn.zhishi.stock.market.domain.MarketOverview;
import java.time.OffsetDateTime;

/**
 * MKT-03 用例：返回同一快照口径的市场广度。
 *
 * <p>快照解析完全复用 {@link MarketOverviewQueryService}，因此 MKT-01 与 MKT-03
 * 不会各自演化出一套"实时 / 归档 / 过期"语义。
 */
public class MarketBreadthQueryService {

    private final MarketOverviewQueryService overviewQueryService;

    public MarketBreadthQueryService(MarketOverviewQueryService overviewQueryService) {
        this.overviewQueryService = overviewQueryService;
    }

    /** {@code snapshotTime} 为 {@code null} 时取当前快照，否则回溯到该时刻的最近批次。 */
    public MarketBreadth getBreadth(String marketCode, OffsetDateTime snapshotTime) {
        MarketOverview snapshot = overviewQueryService.getOverview(marketCode, snapshotTime);
        MarketOverview.BreadthData breadth = snapshot.breadth();
        return new MarketBreadth(
                snapshot.marketCode(),
                breadth.riseCount(),
                breadth.fallCount(),
                breadth.flatCount(),
                breadth.suspendedCount(),
                breadth.limitUpCount(),
                breadth.limitDownCount(),
                snapshot.dataTime(),
                snapshot.dataStatus(),
                snapshot.lastSuccessfulSyncAt(),
                snapshot.snapshotVersion());
    }
}
