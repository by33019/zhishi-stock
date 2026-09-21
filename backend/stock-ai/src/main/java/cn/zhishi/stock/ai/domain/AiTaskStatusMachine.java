package cn.zhishi.stock.ai.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态迁移规则（纯函数）。
 *
 * <h2>为什么是白名单</h2>
 * 判据不是"只要不是终态就能随便跳"。反例是 {@code RUNNING -> COMPLETED}：
 * Worker 崩溃重启后若允许这一步，就会**跳过 VALIDATING**——
 * 一份没经过引用与安全校验的文本被标成成功报告。这类缺陷不会报错，
 * 只会让用户看到一份带编造引用的"成功"报告。
 *
 * <h2>取消是意图，状态是事实</h2>
 * {@code cancel_requested} 与 {@code status} 在库里是两列，本类只描述状态那一列。
 * 取消请求到达时任务可能正在调模型，无法立即中断，因此
 * {@link #cancellable(AiTaskStatus)} 只回答"这个状态还接受取消意图吗"。
 */
public final class AiTaskStatusMachine {

    /**
     * 允许的迁移（白名单）。
     *
     * <p>每一条都对应执行路径上真实存在的一步；没有"顺手加上"的边。
     * 例如 {@code CREATED -> QUEUED} 是"投递成功"那一步，
     * {@code CREATED -> PREPARING} 是"Worker 抢在执行权更新之前就取走了消息"那一步——
     * 两者都存在，所以都要写进来。
     */
    private static final Map<AiTaskStatus, Set<AiTaskStatus>> ALLOWED = allowed();

    private AiTaskStatusMachine() {
    }

    private static Map<AiTaskStatus, Set<AiTaskStatus>> allowed() {
        Map<AiTaskStatus, Set<AiTaskStatus>> map = new EnumMap<>(AiTaskStatus.class);
        map.put(AiTaskStatus.CREATED, EnumSet.of(
                AiTaskStatus.PREPARING,
                AiTaskStatus.QUEUED,
                AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED));
        map.put(AiTaskStatus.PREPARING, EnumSet.of(
                AiTaskStatus.QUEUED,
                AiTaskStatus.RUNNING,
                AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED,
                AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.QUEUED, EnumSet.of(
                // QUEUED -> PREPARING 是**抢占执行权**那一步：
                // UPDATE ai_task SET status='PREPARING' WHERE status IN ('CREATED','QUEUED')。
                // 漏掉这条边，白名单就不再描述真实执行路径了。
                AiTaskStatus.PREPARING,
                AiTaskStatus.RUNNING,
                AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED,
                AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.RUNNING, EnumSet.of(
                // RUNNING -> QUEUED 是**自动重试**那一步：可重试的供应商异常
                // （超时 / 限流 / 5xx）把任务放回队列等下一次尝试，由 attempt_no
                // 与 max_attempts 封顶。它不会跳过任何校验——重试是从头再走一遍。
                AiTaskStatus.QUEUED,
                AiTaskStatus.VALIDATING,
                AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED,
                AiTaskStatus.TIMED_OUT));
        map.put(AiTaskStatus.VALIDATING, EnumSet.of(
                // VALIDATING -> QUEUED 只由**恢复扫描**触发：执行者在校验阶段死掉，
                // 心跳过期后任务被放回队列重跑（有 attempt_no 封顶）。
                // 它不跳过校验——重跑会把 VALIDATING 再走一遍。
                AiTaskStatus.QUEUED,
                AiTaskStatus.COMPLETED,
                AiTaskStatus.CANCELED,
                AiTaskStatus.FAILED,
                AiTaskStatus.TIMED_OUT));
        for (AiTaskStatus terminal : AiTaskStatus.terminalStatuses()) {
            map.put(terminal, EnumSet.noneOf(AiTaskStatus.class));
        }
        return Map.copyOf(map);
    }

    /** 该迁移是否被允许。 */
    public static boolean canTransition(AiTaskStatus from, AiTaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 该状态是否还接受取消意图。
     *
     * <p>终态不再接受：契约 AI-06 要求已完成的任务保持完成并返回
     * {@code effectiveImmediately=false}，即"取消意图收到了，但状态不变"。
     */
    public static boolean cancellable(AiTaskStatus status) {
        return status != null && !status.terminal();
    }

    /**
     * 该状态是否可重试（契约 AI-07：仅 {@code FAILED} 或 {@code TIMED_OUT}）。
     *
     * <p>刻意**不含** {@code CANCELED}：用户主动取消的任务不该被"重试"复活，
     * 那与他的意图相反。
     */
    public static boolean retryable(AiTaskStatus status) {
        return status == AiTaskStatus.FAILED || status == AiTaskStatus.TIMED_OUT;
    }
}
