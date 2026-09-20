package cn.zhishi.stock.news.domain;

import cn.zhishi.stock.common.api.PageData;
import cn.zhishi.stock.market.domain.MarketOverview;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资讯列表分页结果（NEWS-01 / STK-10 / SEC-07 共用），字段与契约
 * "{@code PageData<NewsSummary>}、{@code lastSuccessfulSyncAt}、{@code dataStatus}" 对齐。
 *
 * <p>刻意**扁平**（{@code items} 与分页字段同级）而不是嵌套一个 {@link PageData}：
 * 与 QTE-01 的 {@code StockRanking} 同形，前端少一层解引用。代价是这里重复了分页字段，
 * 因此分页算术仍只由 {@link PageData#slice} 承担，只有一份实现。
 *
 * <p>{@code lastSuccessfulSyncAt} / {@code dataStatus} 描述的是**整批资讯**的新鲜度，
 * 不是这一页的属性。它们与 NEWS-03 的 {@code syncStatus()} **同源**（都来自
 * {@link NewsSyncStatus}），因此"列表页看到的状态与状态页一致"是构造出来的，
 * 而不是靠两个接口各自算一遍。
 *
 * <p>契约 §3.6 要求资讯响应区分 {@code publishedAt} / {@code collectedAt} / 平台最近成功同步时间：
 * 前两者在每条 {@link NewsSummary} 上，第三者在这里。
 */
public record NewsPage(
        List<NewsSummary> items,
        int page,
        int size,
        long total,
        int totalPages,
        boolean hasNext,
        OffsetDateTime lastSuccessfulSyncAt,
        MarketOverview.DataStatus dataStatus) {

    public NewsPage {
        items = List.copyOf(items);
    }

    /** 把分页结果与同步状态合成对外响应；唯一的一处合成点。 */
    public static NewsPage of(PageData<NewsSummary> page, NewsSyncStatus status) {
        return new NewsPage(
                page.items(),
                page.page(),
                page.size(),
                page.total(),
                page.totalPages(),
                page.hasNext(),
                status.lastSuccessfulSyncAt(),
                status.dataStatus());
    }
}
