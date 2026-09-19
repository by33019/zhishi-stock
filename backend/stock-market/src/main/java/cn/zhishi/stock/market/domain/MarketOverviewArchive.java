package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

@FunctionalInterface
public interface MarketOverviewArchive {

    Optional<MarketOverview> findLatest(String marketCode);

    /**
     * 取**不晚于** {@code snapshotTime} 的最近一条归档快照。
     *
     * <p>默认实现退化为"取最新一条，且其 {@code dataTime} 不晚于请求时刻"。
     * 真正的按时间检索由 JDBC 实现走 {@code idx_market_overview_latest} 完成；
     * 默认实现只是让内存实现不必重复这段语义。
     */
    default Optional<MarketOverview> findAt(String marketCode, OffsetDateTime snapshotTime) {
        return findLatest(marketCode)
                .filter(snapshot -> !snapshot.dataTime().isAfter(snapshotTime));
    }

    default void save(MarketOverview snapshot) {
        throw new UnsupportedOperationException("当前市场归档存储只支持读取");
    }
}
