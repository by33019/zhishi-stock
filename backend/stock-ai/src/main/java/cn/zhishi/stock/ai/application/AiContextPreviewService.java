package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextBuildResult;
import cn.zhishi.stock.ai.domain.AiContextBuilder;
import cn.zhishi.stock.ai.domain.AiEvidenceType;

/**
 * AI-02 上下文预览用例：把一次请求翻译成「将使用哪些数据、能不能生成」。
 *
 * <h2>它只做两件事</h2>
 * <ol>
 *   <li><b>校验与解析</b>：整体委托 {@link AiTaskRequestResolver}——AI-03 用的是同一个实例，
 *       因此"什么请求算合法"只有一处实现（M3-07 抽出来的原因）。
 *   <li><b>委托取数</b>：真正取数与固化交给 {@link AiContextBuilder}，
 *       本类**不碰行情、不碰资讯**，也不派生任何统计量。
 * </ol>
 *
 * <h2>数据缺失不抛异常</h2>
 * 核心行情缺失 → {@code canGenerate=false} 并给出 {@code limitations}。
 * 区分标准是"错在请求"还是"错在数据"：预览的用途正是
 * 「在正式消耗配额前展示将使用的数据摘要」（契约 §13.1），
 * 数据缺失时报 400 会让用户看不到"为什么不能生成"。
 */
public class AiContextPreviewService {

    private final AiTaskRequestResolver resolver;
    private final AiContextBuilder contextBuilder;

    public AiContextPreviewService(AiTaskRequestResolver resolver, AiContextBuilder contextBuilder) {
        this.resolver = resolver;
        this.contextBuilder = contextBuilder;
    }

    /**
     * 生成预览。
     *
     * @throws InvalidAiContextQueryException 场景码或分析区间不合法（→ 400）
     * @throws InvalidAiTargetException 目标违反场景规则（→ 400，业务码 {@code AI_TARGET_INVALID}）
     */
    public AiContextPreview preview(AiContextPreviewRequest request) {
        AiResolvedRequest resolved = resolver.resolve(
                request.scene(),
                request.targets(),
                request.analysisStartAt(),
                request.analysisEndAt());

        AiContextBuildResult result = contextBuilder.build(
                resolved.targets(), resolved.analysisStartAt(), resolved.analysisEndAt());

        return new AiContextPreview(
                resolved.targets(),
                result.dataCutoffs(),
                newsCountOf(result),
                result.limitations(),
                result.coreDataAvailable());
    }

    /**
     * 可进入 AI 上下文的资讯条数。
     *
     * <p>由**已经构建好的证据**计数得出，不另起一次查询：另起一次就多了一个
     * "预览说 20 条、报告里只有 18 条"的窗口，而这类不一致不会报错。
     *
     * <p>公告也算资讯：它与媒体报道同属"事件线索"，契约 §13.5 把它们放在同一节里。
     */
    private static int newsCountOf(AiContextBuildResult result) {
        return (int) result.evidenceCandidates().stream()
                .filter(candidate -> candidate.evidenceType() == AiEvidenceType.NEWS
                        || candidate.evidenceType() == AiEvidenceType.ANNOUNCEMENT)
                .count();
    }
}
