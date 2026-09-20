package cn.zhishi.stock.news.application;

import cn.zhishi.stock.news.domain.NewsType;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 资讯列表的筛选条件（NEWS-01 / STK-10 / SEC-07 共用）。
 *
 * <p>字段全部可空，语义统一为"为空即不过滤"。刻意不提供默认值：
 * 默认值会让"调用方忘了传"与"调用方刻意不传"变得不可区分。
 *
 * @param newsTypes 资讯类型；空集合表示不限
 * @param keyword   关键词，匹配标题或摘要（大小写不敏感）
 */
public record NewsQuery(
        Set<NewsType> newsTypes,
        String securityId,
        String sectorId,
        String marketCode,
        OffsetDateTime startAt,
        OffsetDateTime endAt,
        String keyword,
        Integer page,
        Integer size) {

    public static NewsQuery of(
            Set<NewsType> newsTypes,
            String securityId,
            String sectorId,
            String marketCode,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            String keyword,
            Integer page,
            Integer size) {
        return new NewsQuery(
                newsTypes == null ? Set.of() : Set.copyOf(newsTypes),
                securityId, sectorId, marketCode, startAt, endAt, keyword, page, size);
    }
}
