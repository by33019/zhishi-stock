package cn.zhishi.stock.market.domain;

import java.util.Optional;

/**
 * 单只证券日/周/月 K 线的来源端口（STK-07）。
 *
 * <p>实现方**按需生成**：不预生成全市场历史序列（5149 只 × 5 年 ≈ 640 万点，
 * 内存里既存不下也不该存），而是给定证券与区间现算。
 */
@FunctionalInterface
public interface KlineProvider {

    /** 返回指定证券指定区间的 K 线；证券不存在或市场不受支持时返回空。 */
    Optional<KlineSeries> fetch(KlineRequest request);
}
