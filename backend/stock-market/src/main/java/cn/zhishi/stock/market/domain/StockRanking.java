package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 榜单分页结果，字段与 {@code RESTful-API.md} §9.1 QTE-01 的返回参数对齐。
 *
 * <p>刻意**扁平**（{@code items} 与分页字段同级）而不是嵌套一个 {@code PageData}：
 * 契约只写了"{@code PageData<QuoteSnapshot>}、{@code rankingType}、{@code snapshotVersion}…"，
 * 没写明外层形状，扁平让前端少一层解引用。代价是这里重复了分页字段，
 * 因此分页算术仍交给 {@link cn.zhishi.stock.common.api.PageData#slice}，只有一份实现。
 *
 * <p>{@code snapshotVersion} / {@code dataTime} / {@code dataStatus} 是**整批快照**的属性，
 * 不是独立生成的值——它们与 {@code items} 里每一行的 {@code sequence} / {@code dataTime}
 * 同源，因此"整个榜单使用同一已完成快照版本"是构造出来的，而不是靠约定。
 */
public record StockRanking(
        List<QuoteSnapshot> items,
        int page,
        int size,
        long total,
        int totalPages,
        boolean hasNext,
        String rankingType,
        String snapshotVersion,
        OffsetDateTime dataTime,
        MarketOverview.DataStatus dataStatus) {

    public StockRanking {
        items = List.copyOf(items);
    }
}
