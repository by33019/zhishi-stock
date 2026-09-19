package cn.zhishi.stock.market.domain;

import java.util.Optional;

/**
 * 单只证券完整快照的来源端口（STK-04）。
 *
 * <p>与 {@link SecurityQuoteProvider} 的区别：后者按"整批"取数，服务广度计数
 * （一次调用返回的就是同一批次，这是"不混用不同批次"的结构性保证）；
 * 本端口按"单只"取数，服务个股详情首屏。
 */
@FunctionalInterface
public interface QuoteSnapshotProvider {

    /** 返回指定证券的最新快照；证券不存在或市场不受支持时返回空。 */
    Optional<QuoteSnapshot> fetch(String securityId, String marketCode);
}
