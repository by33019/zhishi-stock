package cn.zhishi.stock.market.domain;

import java.util.Optional;

/**
 * 单只证券分时序列的来源端口（STK-06）。
 *
 * <p>与 {@link KlineProvider} 同一条实现约定：按需现算、不预生成全市场
 * 分钟序列（5149 只 × 240 分钟 × 交易日数，内存里既存不下也不该存）。
 */
@FunctionalInterface
public interface IntradayProvider {

    /** 返回指定证券指定交易日的分时序列；证券不存在或市场不受支持时返回空。 */
    Optional<IntradaySeries> fetch(IntradayRequest request);
}
