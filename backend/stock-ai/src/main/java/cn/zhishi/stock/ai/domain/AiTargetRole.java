package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 目标在任务中的角色，取值与 {@code ai_task_target.target_role} 的 CHECK 约束同集合。
 *
 * <p>{@link #CONTEXT} 是**服务端专用**：契约 §13.1 的请求元素表只允许客户端传
 * {@code PRIMARY} / {@code COMPARISON}，并明写"{@code CONTEXT} 仅允许服务端生成"。
 * 这个约束由用例层校验（见 {@code AiContextPreviewService}），不靠枚举的取值集合表达——
 * 枚举是"库里允许存什么"，请求校验是"客户端允许传什么"，两者不是一回事。
 */
public enum AiTargetRole {

    /** 主目标：分析的主体。 */
    PRIMARY,

    /** 对比目标。 */
    COMPARISON,

    /** 上下文目标：服务端为补充背景自动挂上的目标。 */
    CONTEXT;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static Optional<AiTargetRole> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.name().equals(normalized))
                .findFirst();
    }
}
