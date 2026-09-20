package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 任务目标类型，取值与 {@code ai_task_target.target_type} 的 CHECK 约束同集合。
 *
 * <p>与资讯域的 {@code NewsTargetType} 取值相同（{@code MARKET} / {@code SECTOR} /
 * {@code SECURITY}）但**刻意不共用**：两者一个是"任务分析对象"、一个是"资讯关联对象"，
 * 语义不同、约束不同（AI 侧有 {@code target_role} 而资讯侧没有），
 * 合并会让任一侧的取值变化波及另一侧。跨域复用枚举是"看起来省事"的耦合。
 */
public enum AiTargetType {

    /** 市场（当前只有 {@code CN}）。 */
    MARKET,

    /** 板块。 */
    SECTOR,

    /** 证券。注意是 {@code SECURITY} 而不是 {@code STOCK}——与 {@code ai_task_target} 对齐。 */
    SECURITY;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static Optional<AiTargetType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst();
    }
}
