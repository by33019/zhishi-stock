package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次采集的批次。
 *
 * <p>{@code items} 一次全取，而不是"按来源逐个拉"：整批使用同一个 {@code fetchedAt}
 * 作为基准时刻，去重与关联判定因此不会因为跨来源多次取数而出现"前半批用旧时钟、
 * 后半批用新时钟"。与行情域 {@code QuoteBatch} 的"整批同一快照版本"同一条原则。
 *
 * @param fetchedAt 本次取数的基准时刻，由调用方（采集服务）传入，Provider 不自读时钟
 */
public record NewsFeed(List<NewsFeedItem> items, OffsetDateTime fetchedAt) {

    public NewsFeed {
        items = List.copyOf(items);
    }

    public static NewsFeed empty(OffsetDateTime fetchedAt) {
        return new NewsFeed(List.of(), fetchedAt);
    }
}
