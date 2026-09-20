package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资讯详情（NEWS-02）：{@link NewsSummary} 的扩展。
 *
 * <p>比列表多出来的字段是契约 §11.1 明确要求的那几项：{@code languageCode}、
 * 版权/授权提示、{@code rightsExpireAt}，以及"所有确认关联"。
 *
 * @param copyrightNotice 授权提示文案。**由服务端给出**而不是前端拼：
 *                        它必须与来源的授权状态自洽，前端各写一遍就会出现
 *                        "来源已停用但提示还写着授权有效"。
 */
public record NewsDetail(
        String newsId,
        NewsType newsType,
        String title,
        String summary,
        String sourceName,
        NewsSourceType sourceType,
        String authorName,
        OffsetDateTime publishedAt,
        OffsetDateTime collectedAt,
        String originalUrl,
        NewsOriginalAccessStatus originalAccessStatus,
        String languageCode,
        OffsetDateTime rightsExpireAt,
        String copyrightNotice,
        List<NewsRelationSummary> relations) {

    public NewsDetail {
        relations = List.copyOf(relations);
    }
}
