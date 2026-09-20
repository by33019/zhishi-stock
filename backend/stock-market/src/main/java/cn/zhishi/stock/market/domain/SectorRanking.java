package cn.zhishi.stock.market.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 板块排行分页结果，与 {@code RESTful-API.md} §10 SEC-02 的返回参数对齐。
 *
 * <p>刻意**扁平**（{@code items} 与分页字段同级），与 {@link StockRanking} 同构：
 * 契约没写明外层形状，两个排行榜用同一种形状，前端不必为"榜单"和"板块榜"
 * 各写一套解引用。代价是这里重复了 5 个分页字段，因此分页算术仍交给
 * {@link cn.zhishi.stock.common.api.PageData#slice}，只有一份实现。
 *
 * <p>为什么不把两者合成一个泛型记录：一个是 {@code QuoteSnapshot} 列表、
 * 一个是 {@code SectorQuote} 列表，泛型化会让两条链路互相耦合，
 * 而它们的演进节奏并不一致。
 */
public record SectorRanking(
        List<SectorQuote> items,
        int page,
        int size,
        long total,
        int totalPages,
        boolean hasNext,
        String sectorType,
        String rankingType,
        String snapshotVersion,
        OffsetDateTime dataTime,
        MarketOverview.DataStatus dataStatus) {

    public SectorRanking {
        items = List.copyOf(items);
    }
}
