package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import cn.zhishi.stock.market.domain.QuoteProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

public class MarketIngestionService {

    private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofSeconds(30);

    private final QuoteProvider provider;
    private final MarketOverviewStore store;
    private final MarketOverviewArchive archive;
    private final Clock clock;

    public MarketIngestionService(
            QuoteProvider provider,
            MarketOverviewStore store,
            MarketOverviewArchive archive,
            Clock clock) {
        this.provider = provider;
        this.store = store;
        this.archive = archive;
        this.clock = clock;
    }

    public MarketOverview collect(String marketCode) {
        MarketOverview snapshot = provider.fetch(marketCode);
        validate(marketCode, snapshot);
        archive.save(snapshot);
        store.save(snapshot);
        return snapshot;
    }

    private void validate(String requestedMarket, MarketOverview snapshot) {
        if (!requestedMarket.equals(snapshot.marketCode())) {
            throw new IllegalArgumentException("行情快照 marketCode 与请求不一致");
        }
        OffsetDateTime latestAllowed = OffsetDateTime.now(clock).plus(ALLOWED_CLOCK_SKEW);
        if (snapshot.dataTime().isAfter(latestAllowed)) {
            throw new IllegalArgumentException("行情快照 dataTime 超出允许的时钟偏差");
        }
        if (snapshot.snapshotVersion() == null || snapshot.snapshotVersion().isBlank()) {
            throw new IllegalArgumentException("行情快照 snapshotVersion 不能为空");
        }
    }
}
