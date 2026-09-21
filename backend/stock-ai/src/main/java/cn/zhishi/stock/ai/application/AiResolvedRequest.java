package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiSceneDefinition;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 已通过校验、可直接使用的请求。
 *
 * <p>把「场景定义 + 解析后的目标 + 区间」作为一个整体传递，是为了让调用方
 * **不可能**拿到"校验过但目标没解析"或"解析了但场景没校验"的中间状态。
 * 预览与创建都从这个结果出发，于是两者的目标集合逐字段相同。
 */
public record AiResolvedRequest(
        AiScene scene,
        AiSceneDefinition definition,
        List<AiContextTarget> targets,
        OffsetDateTime analysisStartAt,
        OffsetDateTime analysisEndAt) {

    public AiResolvedRequest {
        targets = List.copyOf(targets);
    }
}
