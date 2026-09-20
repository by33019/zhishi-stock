package cn.zhishi.stock.ai.application;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI-02 上下文预览请求（契约 §13.1）。
 *
 * <p>时间区间可为空（表示用场景默认档位）。**两个端点要么都给、要么都不给**：
 * 只给一个会让"区间"变成半个条件，而半截区间的语义（是"从该时刻到现在"还是
 * "只分析这一天"）没有定义，猜一个就是编造。
 *
 * @param scene          场景码
 * @param targets        目标数组
 * @param analysisStartAt 分析区间起点
 * @param analysisEndAt   分析区间终点
 */
public record AiContextPreviewRequest(
        String scene,
        List<AiTargetRequest> targets,
        OffsetDateTime analysisStartAt,
        OffsetDateTime analysisEndAt) {

    public AiContextPreviewRequest {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
