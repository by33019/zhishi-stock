package cn.zhishi.stock.market.domain;

import java.util.Optional;

@FunctionalInterface
public interface MarketOverviewArchive {

    Optional<MarketOverview> findLatest(String marketCode);

    default void save(MarketOverview snapshot) {
        throw new UnsupportedOperationException("当前市场归档存储只支持读取");
    }
}
