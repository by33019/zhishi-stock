package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;

public class MarketOverviewQueryService {

    private final MarketOverviewStore store;
    private final MarketOverviewArchive archive;

    public MarketOverviewQueryService(MarketOverviewStore store, MarketOverviewArchive archive) {
        this.store = store;
        this.archive = archive;
    }

    public MarketOverview getOverview(String marketCode) {
        return store.find(marketCode)
                .orElseGet(() -> archive.findLatest(marketCode)
                        .map(MarketOverview::asStale)
                        .orElseThrow(() -> new MarketDataUnavailableException(marketCode)));
    }
}
