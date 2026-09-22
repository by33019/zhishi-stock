package cn.zhishi.stock.ai.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 一次大模型**调用**的结果，取值与 {@code ai_usage.result_status} 的 CHECK 约束同集合。
 *
 * <h2>它记的是调用，不是任务</h2>
 * 这两个状态刻意分开：{@code ai_task.status} 回答"用户这次分析怎么样了"，
 * {@code ai_usage.result_status} 回答"这一次 Provider 调用花了多少、成没成"。
 * 一份报告可能因为定稿校验被拒（任务 {@code FAILED}），但那次调用本身是成功的、
 * token 是真的花了——把它记成 {@code FAILURE} 会让成本台账少一笔真实开销。
 *
 * <h2>{@code CANCELED} 与 {@code TIMED_OUT} 目前是预留值</h2>
 * 当前执行路径里 {@link LlmProviderPort#complete} 是**阻塞**的：取消与截止时间都在
 * 调用前后的检查点判定，调用进行中不会被中途打断。因此现在只会产出
 * {@code SUCCESS} 与 {@code FAILURE}——供应商超时表现为 {@code FAILURE} +
 * {@code error_category=TIMEOUT}，不是 {@code TIMED_OUT}。
 * 这两个值先在枚举里占位，是因为数据库的 CHECK 约束已经把它们定义出来了；
 * 将来若要真正中断在途调用（取消要立即停止计费），产出的就是它们。
 */
public enum AiUsageResultStatus {

    /** 调用完成并拿到完整结果。 */
    SUCCESS,

    /** 调用失败（含超时、限流、供应商故障、安全拒绝）。细因见 {@code error_category}。 */
    FAILURE,

    /** 调用被取消。 */
    CANCELED,

    /** 调用超时被中断。 */
    TIMED_OUT;

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
