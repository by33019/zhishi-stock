package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 任务状态（契约 §13.5）。
 *
 * <p>迁移规则**不在这里**，在 {@link AiTaskStatusMachine}：状态是数据，迁移是规则。
 * 把规则塞进枚举的实例方法会让"哪些迁移合法"散落在九个枚举常量里，
 * 而这类规则一旦分叉不会报错——只会让某个终态在一条路径上被回退。
 */
public enum AiTaskStatus {

    /** 已创建，尚未投递。 */
    CREATED,
    /** 正在固化上下文。 */
    PREPARING,
    /** 已投递，等待 Worker 取走。 */
    QUEUED,
    /** 正在调用模型。 */
    RUNNING,
    /** 正在做结构、引用与安全校验。 */
    VALIDATING,
    /** 完成，报告已落库。 */
    COMPLETED,
    /** 已取消。 */
    CANCELED,
    /** 失败。 */
    FAILED,
    /** 超时。 */
    TIMED_OUT;

    /**
     * 终态的**唯一**定义。
     *
     * <p>{@link #terminal()} 与 {@link #activeStatuses()} 都从它派生，
     * 于是"活跃 + 终态 = 全部取值"这条恒等式由构造保证，不靠约定维持。
     */
    private static final List<AiTaskStatus> TERMINAL =
            List.of(COMPLETED, CANCELED, FAILED, TIMED_OUT);

    /** 是否为终态。契约 §13.5：完成、取消或失败后不允许回退到运行态。 */
    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /**
     * 占用并发额度的状态（= 非终态）。
     *
     * <p>并发计数（单用户最多 2 个活跃任务）必须用这一份清单，
     * 不能在 SQL 里另写一遍状态枚举——两处清单必然分叉，
     * 而分叉的表现是"配额算错了但接口不报错"。
     */
    public static List<AiTaskStatus> activeStatuses() {
        return Arrays.stream(values()).filter(status -> !status.terminal()).toList();
    }

    /** 终态清单。与 {@link #activeStatuses()} 恰好互补。 */
    public static List<AiTaskStatus> terminalStatuses() {
        return TERMINAL;
    }

    /**
     * 面向用户的阶段说明（契约 §4.4 的 {@code progressStage}）。
     *
     * <p>放在枚举上而不是控制器里：同一状态在 AI-04 与 SSE 的 {@code status} 事件里
     * 都会出现，两处各写一份文案会让同一个状态在两处显示成不同的话，
     * 而前端会据此以为任务真的变了。
     *
     * <p>刻意**不**包含进度百分比：状态机没有可量化的完成度，
     * 编一个"67%"会让用户以为剩余时间是可预测的。
     */
    public String progressStage() {
        return switch (this) {
            case CREATED -> "已创建";
            case PREPARING -> "正在准备数据";
            case QUEUED -> "排队中";
            case RUNNING -> "正在生成分析";
            case VALIDATING -> "正在校验结果";
            case COMPLETED -> "已完成";
            case CANCELED -> "已取消";
            case FAILED -> "已失败";
            case TIMED_OUT -> "已超时";
        };
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static Optional<AiTaskStatus> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(status -> status.name().equals(normalized))
                .findFirst();
    }
}
