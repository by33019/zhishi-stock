package cn.zhishi.stock.market.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * MKT-03 响应体：同一快照口径下的市场广度。
 *
 * <p>所有计数都来自**同一个** {@link MarketOverview} 快照对象，因此"不混用不同批次行情"
 * 是构造上的保证，而不是一句约定。
 *
 * <p>{@code totalCount} 由四态相加派生，不单独存储——快照已把这四态划分完备，
 * 再存一份"总数"只会制造一个可能不一致的第二真相。
 */
public record MarketBreadth(
        String marketCode,
        int riseCount,
        int fallCount,
        int flatCount,
        int suspendedCount,
        int limitUpCount,
        int limitDownCount,
        OffsetDateTime dataTime,
        MarketOverview.DataStatus dataStatus,
        OffsetDateTime lastSuccessfulSyncAt,
        String snapshotVersion) {

    /** 快照覆盖的证券总数：四态之和。 */
    @JsonProperty("totalCount")
    public int totalCount() {
        return riseCount + fallCount + flatCount + suspendedCount;
    }
}
