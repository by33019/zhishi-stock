package cn.zhishi.stock.system.watchlist;

import cn.zhishi.stock.market.domain.MarketOverview;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * WAT-11 自选侧的结果：分组摘要 + 自选行情数组 + 批次数据状态。
 *
 * <p>**不含市场状态**：那是行情域的事，由组合根（controller）从
 * {@code MarketStatusQueryService} 取来后拼进响应视图。这样 {@code stock-system}
 * 只需要依赖 {@code stock-market} 的 domain，不必依赖它的用例层。
 *
 * @param groups          本人全部有效分组（与 WAT-01 同序：{@code sortNo}、{@code groupId}）
 * @param entries         自选项（含行情与证券摘要），按分组顺序、组内 {@code sortNo}
 * @param snapshotVersion 整批快照版本；批次为空时是空串——不编造版本号
 * @param dataStatus      批次数据时效；批次为空时是 {@code UNAVAILABLE}
 * @param dataTime        批次数据截止时间；批次为空时是 {@code null}
 * @param limitations     人可读的降级说明，空列表表示没有任何降级
 */
public record WatchlistOverview(
        List<WatchlistGroup> groups,
        List<WatchlistEntry> entries,
        String snapshotVersion,
        MarketOverview.DataStatus dataStatus,
        OffsetDateTime dataTime,
        List<String> limitations) {
}
