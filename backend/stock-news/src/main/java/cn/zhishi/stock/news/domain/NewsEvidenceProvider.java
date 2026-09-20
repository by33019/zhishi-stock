package cn.zhishi.stock.news.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI 上下文可用的资讯证据来源端口。
 *
 * <h2>为什么端口定义在资讯域</h2>
 * "一条资讯能不能进入 AI 上下文"由两件事共同决定：它是否**可见**
 * （内容状态、去重状态、来源授权、内容授权期、关联确认——契约 §11.2 的五条判据），
 * 以及它的来源是否**允许进入 AI**（{@code news_source.allow_ai_analysis}）。
 * 前者是资讯域的事实，后者是 {@link NewsSource} 的属性。
 *
 * <p>在消费方（{@code stock-ai}）重写一遍这套判据，就出现**两处"哪些资讯可用"的知识**——
 * 而口径分歧**不会报错**，只会让"资讯列表里看得到、AI 却说没有依据"。
 * 因此端口在这里，实现是 {@code NewsQueryService}（它已持有全部判据所需的依赖）。
 *
 * <p>{@code allow_ai_analysis} 的消费侧正是本端口（M3-04 只落库了这个标记）。
 */
public interface NewsEvidenceProvider {

    /**
     * 取与目标**确认关联**、来源授权有效且允许进入 AI 上下文的资讯，按发布时间倒序。
     *
     * <p>调用方必须先自行校验目标存在（AI 侧由 {@code AiContextBuilder} 通过身份解析完成），
     * 因为"目标不存在"在 AI 场景下是 {@code AI_TARGET_INVALID}（400），
     * 而不是资讯域的 404。
     *
     * @param targetType 目标类型
     * @param targetId   目标**对外标识**（{@code sim-600519} / {@code sim-bk0001} / {@code CN}）
     * @param startAt    发布时间的下界（含）；{@code null} 表示不设下界
     * @param endAt      发布时间的上界（含）；{@code null} 表示不设上界
     * @param limit      条数上限，必须为正数；**在过滤链之后截断**，
     *                   否则"最新 N 条里有若干条不允许进 AI"会静默压低可用证据量
     * @return 按 {@code publishedAt DESC, newsId DESC} 排序的证据；无匹配时返回空列表
     * @throws NewsNotFoundException 目标标识无法解析
     */
    List<NewsEvidence> evidenceFor(
            NewsTargetType targetType,
            String targetId,
            OffsetDateTime startAt,
            OffsetDateTime endAt,
            int limit);
}
