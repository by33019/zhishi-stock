package cn.zhishi.stock.news.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code stock_news_relation.target_type='MARKET'} 时 {@code target_id} 的编码规则。
 *
 * <p>为什么需要它：V4 把关联目标的代理键统一定义为 {@code bigint}，但市场没有代理键——
 * 市场代码 {@code CN} 是字符串。于是需要一条**唯一的**字符串 → 数值映射，
 * 否则"写的时候用哈希、读的时候用序号"这类不一致会静默产生一批查不回来的关联。
 *
 * <h2>为什么放在资讯域而不是行情域</h2>
 * 行情域里市场是一个**字符串代码**（{@code marketCode}），它自己不需要数值形式；
 * 需要数值形式的是 V4 的关系表。因此这条规则属于"资讯关联表的编码约定"，
 * 由资讯域定义、由资讯域消费，不存在第二方。将来若行情域也有了市场代理键，
 * 应把本类迁过去并替换实现。
 */
public final class NewsMarketTargets {

    /** MVP 唯一支持的市场。 */
    public static final String CN = "CN";

    private static final long CN_STORAGE_ID = 1L;

    private NewsMarketTargets() {
    }

    /** 市场代码 → 关联表代理键；不支持的市场返回空（不编造编号）。 */
    public static Optional<Long> storageIdOf(String marketCode) {
        if (marketCode == null) {
            return Optional.empty();
        }
        String normalized = marketCode.trim().toUpperCase(Locale.ROOT);
        return CN.equals(normalized) ? Optional.of(CN_STORAGE_ID) : Optional.empty();
    }

    /** 关联表代理键 → 市场代码；未知编号返回空。 */
    public static Optional<String> marketCodeOf(long storageId) {
        return storageId == CN_STORAGE_ID ? Optional.of(CN) : Optional.empty();
    }
}
