package cn.zhishi.stock.market.domain;

import java.util.List;

/**
 * 整批个股快照来源端口。
 *
 * <p>与 {@link QuoteSnapshotProvider} 的区别只在粒度：后者回答"**这一只**是什么行情"，
 * 本端口回答"**这一批**都有哪些行情"。榜单要的是全市场横截面，因此必须整批取。
 *
 * <p>端口**刻意不接收筛选条件**：一次调用返回的就是同一批次，于是契约 QTE-01 要求的
 * "整个榜单使用同一已完成快照版本"由接口形状决定，而不是依赖实现方记得只取一个批次。
 */
@FunctionalInterface
public interface QuoteSnapshotBatchProvider {

    /** 返回指定市场当前已完成批次的全部个股快照；市场不受支持时返回空列表。 */
    List<QuoteSnapshot> fetchBatch(String marketCode);
}
