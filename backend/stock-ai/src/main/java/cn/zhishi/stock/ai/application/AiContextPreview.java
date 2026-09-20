package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiDataCutoff;
import java.util.List;

/**
 * AI-02 上下文预览响应（契约 §13.1）。
 *
 * <p>契约明确它「不返回完整内部 Prompt 或未授权正文」——因此这里只有
 * 目标摘要、数据类别与截止时间、资讯条数、降级说明与能否生成，
 * 没有任何 prompt 正文或资讯正文。
 *
 * @param targets        已解析的目标摘要
 * @param dataCategories 实际取到的数据类别及各自截止时间
 * @param newsCount      可进入 AI 上下文的资讯条数
 * @param limitations    降级说明；为空表示没有降级
 * @param canGenerate    是否具备生成条件（核心行情齐备）
 */
public record AiContextPreview(
        List<AiContextTarget> targets,
        List<AiDataCutoff> dataCategories,
        int newsCount,
        List<String> limitations,
        boolean canGenerate) {

    public AiContextPreview {
        targets = List.copyOf(targets);
        dataCategories = List.copyOf(dataCategories);
        limitations = List.copyOf(limitations);
    }
}
