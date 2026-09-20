package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 场景定义（AI-01 的数组元素）。
 *
 * <p>字段与契约 §13.1 的返回参数逐一对齐：{@code scene} / {@code name} / {@code description} /
 * {@code allowedTargetTypes} / {@code minTargets} / {@code maxTargets} / {@code defaultRange} /
 * {@code questionMaxLength}。
 *
 * <p>{@code defaultRange} 在 JSON 里以 {@link AiAnalysisRange} 的结构出现，
 * 由 {@link AiSceneCatalog} 提供；本记录因此不含时钟，是**纯静态**的。
 */
public record AiSceneDefinition(
        AiScene scene,
        String name,
        String description,
        List<AiTargetType> allowedTargetTypes,
        int minTargets,
        int maxTargets,
        AiAnalysisRange defaultRange,
        int questionMaxLength) {

    public AiSceneDefinition {
        allowedTargetTypes = List.copyOf(allowedTargetTypes);
        if (minTargets < 1) {
            throw new IllegalArgumentException("minTargets 必须为正数：" + minTargets);
        }
        if (maxTargets < minTargets) {
            throw new IllegalArgumentException(
                    "maxTargets 不得小于 minTargets：" + maxTargets + " < " + minTargets);
        }
        if (questionMaxLength < 1) {
            throw new IllegalArgumentException("questionMaxLength 必须为正数：" + questionMaxLength);
        }
    }

    /** 目标类型是否被该场景接受。 */
    public boolean allowsTargetType(AiTargetType targetType) {
        return allowedTargetTypes.contains(targetType);
    }

    /** 目标数量是否落在该场景允许的区间内。 */
    public boolean allowsTargetCount(int count) {
        return count >= minTargets && count <= maxTargets;
    }

    /** 该场景是否要求恰好一个主目标（当前全部场景都要求）。 */
    public boolean requiresSinglePrimary() {
        return true;
    }
}
