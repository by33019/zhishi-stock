package cn.zhishi.stock.market.domain;

import java.util.Optional;

@FunctionalInterface
public interface MarketOverviewStore {

    Optional<MarketOverview> find(String marketCode);

    default void save(MarketOverview snapshot) {
        throw new UnsupportedOperationException("当前市场快照存储只支持读取");
    }
}
