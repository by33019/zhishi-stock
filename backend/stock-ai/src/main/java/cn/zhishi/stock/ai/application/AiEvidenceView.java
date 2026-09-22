package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiEvidence;
import cn.zhishi.stock.ai.domain.AiEvidenceAccessStatus;
import cn.zhishi.stock.news.domain.NewsUrlPolicy;
import java.time.OffsetDateTime;

/**
 * 报告的来源引用（契约 §HIS-07），HIS-07 的响应元素。
 *
 * <h2>字段比 {@code AiEvidence} 少，且是刻意的</h2>
 * 契约 §HIS-07 只要求 8 个字段。{@code evidenceId} / {@code reportId} /
 * {@code contextSnapshotId} / {@code sourceObjectType} / {@code sourceObjectId} /
 * {@code contentHash} **不外发**：
 *
 * <ul>
 *   <li>前三个是内部代理键，前端拿到也没有能用的路径（证据没有独立资源 URL）；
 *   <li>{@code sourceObjectId} 会暴露"这条证据对应哪个内部资讯/证券"，而它已经是
 *       一次授权判断的结果——把原料给出去等于让调用方自行复核我们的授权结论；
 *   <li>{@code contentHash} 是入库幂等与去重用的，属于实现细节。
 * </ul>
 *
 * 这与 {@code AiReportDetail} 的做法一致：出参是**投影**，不是聚合的镜像。
 *
 * <h2>两种"不给链接"共用同一结果</h2>
 * {@code sourceUrl} 在以下两种情况下为 {@code null}，且**不区分**：
 *
 * <ul>
 *   <li>{@link AiEvidenceAccessStatus#RESTRICTED}：授权受限，只保留摘要。
 *       这是该状态的定义，不是可选行为；
 *   <li>链接不在协议白名单内（非 {@code https}，或形如 {@code javascript:} 的伪协议）。
 *       契约 §24 要求"外链只允许白名单协议"，而校验放在出口能同时覆盖
 *       "候选固化时就带了脏链接"这条路径。
 * </ul>
 *
 * 区分这两者没有收益：对用户而言结果都是"这里没有可点的原文"，而多给一个
 * 字段说明"为什么没有"只是在教前端认识我们的内部判据。
 *
 * <h2>{@code evidenceNo} 是 int 而不是字符串</h2>
 * 项目约定「业务 ID 用 Snowflake 字符串」针对的是 19 位主键；{@code evidenceNo}
 * 是报告内从 1 开始的引用序号（正文里的 {@code [1]}），量级只有个位数，
 * 用 int 表达"它是个小整数"比塞进字符串更准确。
 *
 * @param evidenceType  {@code QUOTE} / {@code KLINE} / {@code NEWS} / {@code ANNOUNCEMENT} /
 *                      {@code BUSINESS} / {@code SECTOR} / {@code RULE}
 * @param accessStatus  {@code AVAILABLE} / {@code UNAVAILABLE} / {@code RESTRICTED}
 */
public record AiEvidenceView(
        int evidenceNo,
        String evidenceType,
        String sourceTitle,
        String sourceUrl,
        String evidenceSummary,
        OffsetDateTime sourcePublishedAt,
        OffsetDateTime dataTime,
        String accessStatus) {

    public static AiEvidenceView from(AiEvidence evidence) {
        return new AiEvidenceView(
                evidence.evidenceNo(),
                evidence.evidenceType().name(),
                evidence.sourceTitle(),
                exposableUrl(evidence),
                evidence.evidenceSummary(),
                evidence.sourcePublishedAt(),
                evidence.dataTime(),
                evidence.accessStatus().name());
    }

    /**
     * 可以对外呈现的原文地址；没有就是 {@code null}。
     *
     * <p>白名单复用资讯域的 {@code NewsUrlPolicy} 而**不是**在 AI 域再写一份：
     * 两处各写一份时，"哪些协议能出现"就有两个定义，而分叉不会报错——
     * 只会让某一条路径悄悄把 {@code http} 链接放给用户。
     */
    private static String exposableUrl(AiEvidence evidence) {
        if (evidence.accessStatus() == AiEvidenceAccessStatus.RESTRICTED) {
            return null;
        }
        String url = evidence.sourceUrl();
        return NewsUrlPolicy.isAllowed(url) ? url : null;
    }
}
