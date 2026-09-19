package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.MarketOverviewArchive;
import cn.zhishi.stock.market.domain.MarketOverviewStore;
import java.time.OffsetDateTime;
import java.util.Optional;

public class MarketOverviewQueryService {

    private final MarketOverviewStore store;
    private final MarketOverviewArchive archive;

    public MarketOverviewQueryService(MarketOverviewStore store, MarketOverviewArchive archive) {
        this.store = store;
        this.archive = archive;
    }

    /** 取当前快照：实时存储优先，未命中则回落到归档并标记为过期。 */
    public MarketOverview getOverview(String marketCode) {
        return getOverview(marketCode, null);
    }

    /**
     * 取指定时刻的快照，用于时间回溯。
     *
     * <p>{@code snapshotTime} 为 {@code null} 时行为与 {@link #getOverview(String)} 完全一致。
     * 给出时刻时：实时存储里的快照必须**不晚于**该时刻才可用，否则一律回到归档里找
     * "不晚于该时刻的最近一条"，并标记为过期——回溯取到的一定是历史批次，
     * 因此不存在"最新的实时快照"这种说法。
     */
    public MarketOverview getOverview(String marketCode, OffsetDateTime snapshotTime) {
        if (snapshotTime == null) {
            return store.find(marketCode).orElseGet(() -> findArchived(marketCode));
        }
        Optional<MarketOverview> live = store.find(marketCode)
                .filter(snapshot -> !snapshot.dataTime().isAfter(snapshotTime));
        return live.orElseGet(() -> findArchivedAt(marketCode, snapshotTime));
    }

    private MarketOverview findArchived(String marketCode) {
        try {
            return archive.findLatest(marketCode)
                    .map(MarketOverview::asStale)
                    .orElseThrow(() -> new MarketDataUnavailableException(marketCode));
        } catch (MarketDataUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MarketDataUnavailableException(marketCode, exception);
        }
    }

    private MarketOverview findArchivedAt(String marketCode, OffsetDateTime snapshotTime) {
        try {
            return archive.findAt(marketCode, snapshotTime)
                    .map(MarketOverview::asStale)
                    .orElseThrow(() -> new MarketDataUnavailableException(marketCode));
        } catch (MarketDataUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MarketDataUnavailableException(marketCode, exception);
        }
    }
}
