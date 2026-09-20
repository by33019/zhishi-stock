package cn.zhishi.stock.news.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 关联目标类型，与 {@code stock_news_relation.target_type} 的 CHECK 约束同集合。
 *
 * <p>{@code target_id} 的含义随本枚举变化，是典型的"多态外键"：
 * {@code SECURITY} 与 {@code SECTOR} 存的是 bigint 代理键（与
 * {@code user_watchlist_item.security_id} 同一套），{@code MARKET} 存的是市场代码的哈希或固定值。
 *
 * <p>因此**任何消费 {@code targetId} 的代码都必须先看 {@code targetType}**——
 * 把板块 id 当证券 id 去查证券主数据会静默查空，不会报错。
 */
public enum NewsTargetType {

    /** 个股关联。{@code target_id} = 证券代理键（bigint）。 */
    SECURITY,

    /** 板块关联。{@code target_id} = 板块代理键（bigint）。 */
    SECTOR,

    /** 市场整体关联（大盘、政策）。{@code target_id} = 市场代码的稳定数值编码。 */
    MARKET;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 解析类型代码，大小写不敏感；未知取值返回空，**不回落默认值**。 */
    public static Optional<NewsTargetType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst();
    }
}
