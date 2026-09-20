package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;

/**
 * 可进入 AI 上下文的资讯证据（{@code NewsEvidenceProvider} 的返回元素）。
 *
 * <h2>为什么字段比 {@link NewsSummary} 少</h2>
 * AI 证据**不需要**分页元信息、关联列表、采集时间与作者。把整份 {@code NewsSummary}
 * 传过去会让"AI 到底看到了什么"变得难以审查——而架构 §11.4 明确要求
 * 「只发送完成当前分析所需的行情、资讯摘要、标的资料和去标识化追问上下文」。
 * 这个记录就是那句话的形状。
 *
 * <p>{@code originalUrl} 保留：它是**服务端**在生成引用时使用的地址，
 * 与模型无关（模型看到的是 {@code LlmEvidence}，那里没有地址字段）。
 *
 * @param newsId                资讯 ID
 * @param newsType              资讯类型
 * @param title                 标题
 * @param summary               授权范围内的摘要
 * @param sourceName            来源名称
 * @param publishedAt           发布时间
 * @param originalUrl           原文地址
 * @param originalAccessStatus  原文可访问状态
 */
public record NewsEvidence(
        long newsId,
        NewsType newsType,
        String title,
        String summary,
        String sourceName,
        OffsetDateTime publishedAt,
        String originalUrl,
        NewsOriginalAccessStatus originalAccessStatus) {
}
