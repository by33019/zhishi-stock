package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 采集端口返回的**原始**条目：未去重、未解析关联、未校验授权。
 *
 * <p>刻意与 {@link NewsArticle} 分开：{@code NewsArticle} 是**已入库的事实**
 * （有 newsId、有指纹、有去重结论），{@code NewsFeedItem} 是**来源侧的声称**。
 * 混用一个类型会让"来源说这条稿件 id 是 X"与"我们库里这条稿件的 id 是 Y"变成同一个字段。
 *
 * <p>{@code relatedSecurityCodes} / {@code relatedSectorCodes} / {@code marketCode}
 * 是**来源侧的结构化提示**，不是我们的推断。由它们产出的关联是
 * {@link NewsRelationMethod#EXPLICIT}；由标题/摘要文本匹配产出的才是
 * {@link NewsRelationMethod#RULE}。区分二者是审计要求（见 {@link NewsRelationMethod}）。
 *
 * @param sourceContentId 来源侧稿件唯一标识；与 {@code sourceCode} 一起构成"来源 ID 幂等"的键
 * @param originalUrl     原文地址；契约 §11.2 要求只放行白名单协议，由用例层校验
 */
public record NewsFeedItem(
        String sourceCode,
        String sourceContentId,
        NewsType newsType,
        String title,
        String summary,
        String authorName,
        String originalUrl,
        String languageCode,
        OffsetDateTime publishedAt,
        List<String> relatedSecurityCodes,
        List<String> relatedSectorCodes,
        String marketCode) {

    public NewsFeedItem {
        relatedSecurityCodes = relatedSecurityCodes == null
                ? List.of()
                : List.copyOf(relatedSecurityCodes);
        relatedSectorCodes = relatedSectorCodes == null
                ? List.of()
                : List.copyOf(relatedSectorCodes);
        if (languageCode == null || languageCode.isBlank()) {
            languageCode = "zh-CN";
        }
    }
}
