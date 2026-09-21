package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * AI 任务聚合（对应 V6 的 {@code ai_task} + {@code ai_task_target}）。
 *
 * <p>字段与表一一对应而不是"只暴露接口要用的那几个"：Worker 执行时需要场景、区间、
 * 问题、目标、尝试次数、截止时间与心跳，接口需要状态与错误摘要——两套视图各自裁剪
 * 会让"某个字段只有一边有"变成常态，而这类差异不会报错。
 *
 * <p>{@code targets} 复用 {@link AiContextTarget} 而不是另建一个 record：
 * 它就是"任务的目标"，只是多了一个顺序，而顺序由列表下标表达。
 *
 * <h2>刻意**不**携带 {@code reportId}</h2>
 * 早先这里有一个 {@code reportId} 组件，而 {@code ai_task} 表里根本没有这一列——
 * 单测的内存桩是照着自己对契约的理解手写的，所以它一路绿灯，
 * 直到真库集成测试报出 {@code Unknown column 'report_id' in 'field list'}。
 *
 * <p>正确的修法是**删掉它**，而不是给 {@code ai_task} 补一列：
 * V6 把"任务的报告是哪一个"唯一地建模在 {@code ai_report.task_id} 上
 * （{@code UNIQUE INDEX uk_ai_report_task}），一项任务最多一个最终报告。
 * 再在 {@code ai_task} 里存一份，同一个事实就有两处答案——而两处答案分歧
 * 不会报错，只会让"任务说已完成、报告查不到"这种状态悄悄存在。
 * 需要报告 ID 的地方（契约 §4.4 的 {@code reportId} 字段）走
 * {@code AiReportStore.findByTask(taskId)} 解析。
 *
 * <h2>{@code version} 是乐观锁，不是审计字段</h2>
 * {@code ai_task} 里所有状态推进都由 {@link AiTaskStore#save} 以
 * {@code WHERE id = ? AND version = ?} 落库：Worker 与恢复扫描可能同时看到同一个任务，
 * 后写的那个会拿到 0 行影响并放弃。没有这一列，两个执行者都会以为自己成功了，
 * 而报告会被写两遍（或状态被回退）。
 *
 * <p><b>这里持有的 {@code version} 是「库里那一行的当前版本」，不是「写入后的版本」。</b>
 * 推进由数据库的 {@code SET version = version + 1} 负责，写入成功后由存储层用
 * {@link #withVersion} 把新版本交回来。这个方向不能反：域里预先加一、而 SQL 又拿它去
 * {@code WHERE}，匹配的就是一个**还不存在**的版本——{@code save} 会永远返回「冲突」，
 * 心跳一次都写不进去，而所有内存桩都看不见这件事。
 *
 * <h2>另外两列同样刻意不携带</h2>
 * {@code priority_no}（队列优先级）与 {@code updated_at}（数据库自动维护）没有进本 record：
 * 前者本轮没有排序逻辑读它，后者由 MySQL 的 {@code ON UPDATE} 负责。
 * 带上一个恒为默认值的字段，会让"这个字段有没有被真的用上"变得看不出来。
 */
public record AiTask(
        long taskId,
        String requestId,
        long sessionId,
        long userId,
        Long retryOfTaskId,
        AiScene scene,
        String question,
        OffsetDateTime analysisStartAt,
        OffsetDateTime analysisEndAt,
        AiTaskStatus status,
        boolean cancelRequested,
        int attemptNo,
        int maxAttempts,
        String providerCode,
        String modelCode,
        String traceId,
        OffsetDateTime createdAt,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime firstChunkAt,
        OffsetDateTime validatingAt,
        OffsetDateTime heartbeatAt,
        OffsetDateTime deadlineAt,
        OffsetDateTime completedAt,
        String errorCategory,
        String errorCode,
        String errorMessage,
        int version,
        List<AiContextTarget> targets) {

    public AiTask {
        targets = List.copyOf(targets);
    }

    /** 是否还能再自动重试一次（V6 默认 {@code max_attempts = 2}）。 */
    public boolean canAttemptAgain() {
        return attemptNo < maxAttempts;
    }

    /** 是否仍是活跃任务（占用单用户并发额度）。 */
    public boolean active() {
        return !status.terminal();
    }

    /**
     * 是否已超过截止时间。
     *
     * <p>没有 {@code deadlineAt} 时返回 {@code false}——"没有约定截止时间"不等于"已经超时"，
     * 把它当成超时会让一个刚创建的任务立刻被恢复扫描判死。
     */
    public boolean pastDeadline(OffsetDateTime now) {
        return deadlineAt != null && now.isAfter(deadlineAt);
    }

    /** 目标数量（报告与摘要都按它校验）。 */
    public int targetCount() {
        return targets.size();
    }

    // ---------- 状态推进 ----------
    //
    // 这些方法把「某个状态该同时写哪几列」收在域里。执行器若自己拼 30 个字段，
    // 每加一个状态就要再拼一次，而漏写一列不会报错——只会在恢复扫描里表现成
    // "心跳永远是旧的，任务被反复重投"。
    //
    // 它们**不**改 version：数据库的 SET version = version + 1 负责推进，
    // 写入成功后由 {@link AiTaskStore#save} 用 {@link #withVersion} 把新版本交回来。

    /**
     * 推进到非终态。
     *
     * <p>{@code heartbeatAt} 每次推进都刷新——恢复扫描正是按它判断执行者是否还活着。
     * {@code startedAt} / {@code validatingAt} 只在第一次进入对应状态时写。
     *
     * @throws IllegalStateException 该迁移不在白名单里
     */
    public AiTask advance(AiTaskStatus next, OffsetDateTime at) {
        if (!AiTaskStatusMachine.canTransition(status, next)) {
            throw new IllegalStateException("非法的状态迁移：" + status + " → " + next);
        }
        return copy(
                next,
                cancelRequested,
                attemptNo,
                next == AiTaskStatus.RUNNING && startedAt == null ? at : startedAt,
                firstChunkAt,
                next == AiTaskStatus.VALIDATING && validatingAt == null ? at : validatingAt,
                at,
                null,
                errorCategory,
                errorCode,
                errorMessage,
                version);
    }

    /** 记录首次片段时间（契约 §13.5 的「首段 P95 ≤ 5 秒」要有数据可测）。 */
    public AiTask recordFirstChunk(OffsetDateTime at) {
        if (firstChunkAt != null) {
            return this;
        }
        return copy(
                status, cancelRequested, attemptNo, startedAt, at, validatingAt, at,
                null, errorCategory, errorCode, errorMessage, version);
    }

    /** 刷新心跳（执行中每个片段都刷）。 */
    public AiTask heartbeat(OffsetDateTime at) {
        return copy(
                status, cancelRequested, attemptNo, startedAt, firstChunkAt, validatingAt, at,
                null, errorCategory, errorCode, errorMessage, version);
    }

    /**
     * 成功完成。
     *
     * <p>刻意不接收报告 ID：报告与任务的关联存在 {@code ai_report.task_id}（唯一索引），
     * 由报告的写入方负责。任务这一侧只需要如实记下"我完成了"。
     */
    public AiTask complete(OffsetDateTime at) {
        requireTransition(AiTaskStatus.COMPLETED);
        return copy(
                AiTaskStatus.COMPLETED, cancelRequested, attemptNo, startedAt, firstChunkAt,
                validatingAt, at, at, null, null, null, version);
    }

    /** 置取消意图（不改状态）。Worker 在检查点用 {@link #canceled} 兑现。 */
    public AiTask withCancelRequested() {
        return copy(
                status, true, attemptNo, startedAt, firstChunkAt, validatingAt, heartbeatAt,
                completedAt, errorCategory, errorCode, errorMessage, version);
    }

    /** 兑现取消。 */
    public AiTask canceled(OffsetDateTime at) {
        requireTransition(AiTaskStatus.CANCELED);
        return copy(
                AiTaskStatus.CANCELED, cancelRequested, attemptNo, startedAt, firstChunkAt,
                validatingAt, at, at, errorCategory, errorCode, errorMessage, version);
    }

    /** 失败。{@code category} / {@code code} 必须同时给出——只有其一的失败记录查不出原因。 */
    public AiTask failed(
            LlmErrorCategory category, String code, String message, OffsetDateTime at) {
        requireTransition(AiTaskStatus.FAILED);
        return copy(
                AiTaskStatus.FAILED, cancelRequested, attemptNo, startedAt, firstChunkAt,
                validatingAt, at, at, category.name(), code, truncate(message), version);
    }

    /** 超时。 */
    public AiTask timedOut(OffsetDateTime at) {
        return timedOut("任务超过截止时间仍未完成", at);
    }

    /**
     * 超时，并写明是**哪一种**超时。
     *
     * <p>「截止时间已过」与「尝试次数用尽」是两种不同的事实，共用一句说明会让
     * 用户在任务详情里读到一句不成立的解释。状态与错误码仍然是同一个
     * （契约 §13.5 只定义了 {@code TIMED_OUT} 这一个终态），差别只在说明文字。
     */
    public AiTask timedOut(String reason, OffsetDateTime at) {
        requireTransition(AiTaskStatus.TIMED_OUT);
        return copy(
                AiTaskStatus.TIMED_OUT, cancelRequested, attemptNo, startedAt, firstChunkAt,
                validatingAt, at, at, LlmErrorCategory.TIMEOUT.name(), "AI_TASK_TIMED_OUT",
                truncate(reason), version);
    }

    /**
     * 重新排队（可重试的供应商异常）。
     *
     * <p>清掉 {@code startedAt} 与心跳：下一次尝试会重新写。不清的话，
     * 恢复扫描会看到一个"很久以前开始、心跳很旧"的 QUEUED 任务，把它当成死执行者。
     */
    public AiTask requeued(OffsetDateTime at) {
        // 三个状态能回队列，都对应真实路径：
        //   PREPARING / RUNNING —— 执行器自己遇到可重试的供应商异常；
        //   VALIDATING          —— 恢复扫描发现执行者在校验阶段死掉（心跳过期）。
        // QUEUED 与终态不在其列：前者已经在队列里，后者不该被复活。
        if (status != AiTaskStatus.PREPARING
                && status != AiTaskStatus.RUNNING
                && status != AiTaskStatus.VALIDATING) {
            throw new IllegalStateException("只有执行中的任务可以重新排队，当前：" + status);
        }
        return copy(
                AiTaskStatus.QUEUED, cancelRequested, attemptNo, null, null, null, null,
                null, errorCategory, errorCode, errorMessage, version);
    }

    private void requireTransition(AiTaskStatus next) {
        if (!AiTaskStatusMachine.canTransition(status, next)) {
            throw new IllegalStateException("非法的状态迁移：" + status + " → " + next);
        }
    }

    /** {@code error_message} 是 {@code varchar(1000)}，超长会让整条 UPDATE 失败。 */
    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /**
     * 换掉若干列，返回新聚合。
     *
     * <p>{@code nextVersion} 由调用方给出而不是在这里加一：绝大多数推进保持版本不变
     * （推进由数据库做），只有 {@link #withVersion} 会显式写入一个新值。
     */
    private AiTask copy(
            AiTaskStatus nextStatus,
            boolean nextCancelRequested,
            int nextAttemptNo,
            OffsetDateTime nextStartedAt,
            OffsetDateTime nextFirstChunkAt,
            OffsetDateTime nextValidatingAt,
            OffsetDateTime nextHeartbeatAt,
            OffsetDateTime nextCompletedAt,
            String nextErrorCategory,
            String nextErrorCode,
            String nextErrorMessage,
            int nextVersion) {
        return new AiTask(
                taskId,
                requestId,
                sessionId,
                userId,
                retryOfTaskId,
                scene,
                question,
                analysisStartAt,
                analysisEndAt,
                nextStatus,
                nextCancelRequested,
                nextAttemptNo,
                maxAttempts,
                providerCode,
                modelCode,
                traceId,
                createdAt,
                queuedAt,
                nextStartedAt,
                nextFirstChunkAt,
                nextValidatingAt,
                nextHeartbeatAt,
                deadlineAt,
                nextCompletedAt,
                nextErrorCategory,
                nextErrorCode,
                nextErrorMessage,
                nextVersion,
                targets);
    }

    /**
     * 以**写入后的乐观锁版本**返回同一个任务（其余字段不变）。
     *
     * <p>只应由存储层在 {@code UPDATE} 影响 1 行之后调用。写入成功后数据库执行的是
     * {@code SET version = version + 1}，手里这份必须同步跟上，否则下一次
     * {@link AiTaskStore#save} 会拿一个已经不存在的版本去匹配，而它只会安静地返回
     * 「冲突」——表现为「心跳永远刷不进去、状态推进偶尔丢失」，两者都不会报错。
     */
    public AiTask withVersion(int version) {
        return copy(
                status, cancelRequested, attemptNo, startedAt, firstChunkAt, validatingAt,
                heartbeatAt, completedAt, errorCategory, errorCode, errorMessage, version);
    }
}
