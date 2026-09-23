package cn.zhishi.stock.market.application;

import cn.zhishi.stock.market.domain.MarketOverview;
import cn.zhishi.stock.market.domain.QuoteSnapshot;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 榜单的**全量**结果（不分页），字段与 {@link StockRanking} 的批次属性部分逐项相同。
 *
 * <h2>为什么不复用 {@link StockRanking}</h2>
 * {@code StockRanking} 带着 {@code page} / {@code size} / {@code totalPages} 等分页字段。
 * 导出没有"第几页"这个概念——用它承载全量结果，就必须往里塞一组毫无意义的
 * {@code page=1, size=total}，而"导出到底取了多少行"会因此变得含糊。
 *
 * <h2>为什么必须是同一个用例产出</h2>
 * 导出的口径（筛选哪些行、按什么排、取整批还是取一页）**只能有一处实现**。
 * 若导出自己按 {@code rankingType} 再排一遍，就会出现"页面上第一名、文件里第三名"
 * 这种只在数据同值时暴露的偏差，而两边各自看都正常。
 * 因此本记录由 {@link StockRankingQueryService} 的两个入口共用同一段筛选与排序产生。
 */
public record StockRankingDataset(
        List<QuoteSnapshot> items,
        String rankingType,
        String snapshotVersion,
        OffsetDateTime dataTime,
        MarketOverview.DataStatus dataStatus) {

    public StockRankingDataset {
        items = List.copyOf(items);
    }

    public int rowCount() {
        return items.size();
    }
}
