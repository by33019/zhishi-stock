package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskEventStream;
import cn.zhishi.stock.ai.domain.AiTaskEventType;
import cn.zhishi.stock.ai.domain.AiTaskQueue;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 恢复扫描（M3-07）。
 *
 * <h2>它修的是哪一类缺陷</h2>
 * 队列是「至少一次」投递，执行者可能在任何一步死掉：拿到消息后崩、调模型时崩、
 * 写报告前崩。这类故障**不会产生任何错误记录**——任务既不会失败也不会完成，
 * 前端就永远显示「正在生成分析」。它是本域唯一会表现为「什么都没发生」的缺陷。
 *
 * <h2>三类候选，三种处置</h2>
 * <ul>
 *   <li>{@code QUEUED} 且消息丢了 —— 只需**重新投递**。状态不动：它本来就该在队列里。
 *   <li>非 {@code QUEUED} 且心跳过期 —— 执行者死了，把任务放回队列重跑（有次数封顶）。
 *   <li>已置取消意图 —— 兑现成 {@code CANCELED}，不投递。用户已经不要了，
 *       投出去只会白烧一次模型调用。
 * </ul>
 *
 * <h2>为什么「次数用尽」必须在这里挡住</h2>
 * {@code ai_task} 上有 {@code ck_ai_task_attempts (attempt_no <= max_attempts)}。
 * 把一个次数已用尽的任务重新投出去，它会先被投递、再被
 * {@link AiTaskStore#claimForExecution} 的条件更新挡下（那里也有
 * {@code attempt_no < max_attempts}）——看起来无害。但真正的风险在**扫描本身**：
 * 一旦有人把这条守卫去掉，第 3 次抢占就会撞 CHECK 约束，抛的是 SQL 异常，
 * 于是整个扫描批次崩掉，同批次里其他本该被救活的任务一起陪葬。
 * 所以判据在这里，不在数据库的错误里。
 *
 * <h2>并发安全靠乐观锁，不靠「只有一个实例在跑」</h2>
 * 恢复扫描可能被多个实例同时执行（定时任务没有分布式锁）。两条实例同时看到一个
 * 心跳过期的任务时，只有 {@link AiTaskStore#save} 影响 1 行的那个才算处理成功。
 * **投递必须发生在写成功之后**：写失败还投递，会让同一个任务被反复重投。
 */
public class AiTaskRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTaskRecoveryService.class);

    /** 与 {@code AiTask#timedOut} 写入的错误码一致；事件里的 {@code errorCode} 必须同源。 */
    private static final String TIMEOUT_CODE = "AI_TASK_TIMED_OUT";

    private static final String DEADLINE_REASON = "任务超过截止时间仍未完成";

    private static final String ATTEMPTS_REASON = "任务已用尽全部尝试次数仍未完成";

    /**
     * 一次扫描的结果。
     *
     * <p>{@code scanned} 是候选数而不是处理数：{@code scanned - handled()} 就是
     * 「看了一眼但没动」的数量，包含乐观锁冲突与状态已自愈两种。把它分开记，
     * 是为了在"恢复扫描一直很忙"时能分清是任务真的多，还是判据太宽。
     */
    public record RecoveryReport(int scanned, int requeued, int canceled, int timedOut) {

        /** 本批次真正产生了状态变化或重新投递的任务数。 */
        public int handled() {
            return requeued + canceled + timedOut;
        }
    }

    /** 单个任务的处置结果。 */
    private enum Action {
        /** 没有动作（乐观锁冲突，或状态已不满足处置条件）。 */
        SKIPPED,
        REQUEUED,
        CANCELED,
        TIMED_OUT
    }

    private final AiTaskStore tasks;
    private final AiTaskEventStream events;
    private final AiTaskQueue queue;
    private final Clock clock;
    private final long queueLostAfterMinutes;
    private final long heartbeatLostAfterMinutes;
    private final int batchLimit;

    public AiTaskRecoveryService(
            AiTaskStore tasks,
            AiTaskEventStream events,
            AiTaskQueue queue,
            Clock clock,
            long queueLostAfterMinutes,
            long heartbeatLostAfterMinutes,
            int batchLimit) {
        this.tasks = tasks;
        this.events = events;
        this.queue = queue;
        this.clock = clock;
        this.queueLostAfterMinutes = queueLostAfterMinutes;
        this.heartbeatLostAfterMinutes = heartbeatLostAfterMinutes;
        this.batchLimit = batchLimit;
    }

    /**
     * 扫一批并处置。
     *
     * <p>批量上限由 {@code batchLimit} 给出而不是"扫全部"：恢复扫描跑在定时任务里，
     * 一次拉几十万行会让这一轮永远跑不完，而下一轮又开始了。
     */
    public RecoveryReport recover() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<AiTask> candidates = tasks.findRecoverable(
                now.minusMinutes(queueLostAfterMinutes),
                now.minusMinutes(heartbeatLostAfterMinutes),
                batchLimit);

        int requeued = 0;
        int canceled = 0;
        int timedOut = 0;
        for (AiTask task : candidates) {
            Action action;
            try {
                action = recoverOne(task, now);
            } catch (RuntimeException exception) {
                // 一个坏任务不该让整批恢复停摆：同批次里其他任务本来能被救活。
                // 但绝不静默——否则"某个任务每轮扫描都炸"会表现成"任务一直卡住"。
                LOGGER.error("恢复扫描处置单个任务失败，跳过：taskId={} 状态={}",
                        task.taskId(), task.status(), exception);
                continue;
            }
            switch (action) {
                case REQUEUED -> requeued++;
                case CANCELED -> canceled++;
                case TIMED_OUT -> timedOut++;
                case SKIPPED -> {
                    // 有意留空：什么都没发生也是一种结果，不记日志以免每轮刷屏。
                }
            }
        }

        RecoveryReport report = new RecoveryReport(candidates.size(), requeued, canceled, timedOut);
        if (report.handled() > 0) {
            LOGGER.info(
                    "AI 任务恢复扫描：候选 {}，重投 {}，兑现取消 {}，判超时 {}",
                    report.scanned(), report.requeued(), report.canceled(), report.timedOut());
        }
        return report;
    }

    /**
     * 单个任务的处置顺序。
     *
     * <p>顺序是刻意的，四步之间不能互换：
     * <ol>
     *   <li><b>取消意图优先</b>——用户已经不要了，投出去或判超时都是在浪费。
     *   <li><b>截止时间其次</b>——一个已过截止的任务即使还有次数，重投也只会再超时一次。
     *   <li><b>次数封顶再次</b>——没有次数就没有"下一次"，重投是无效动作（且撞 CHECK 约束）。
     *   <li>最后才轮到重投。
     * </ol>
     */
    private Action recoverOne(AiTask task, OffsetDateTime now) {
        if (task.cancelRequested()) {
            return cancel(task, now);
        }
        if (task.pastDeadline(now)) {
            return timeOut(task, now, DEADLINE_REASON);
        }
        if (!task.canAttemptAgain()) {
            return timeOut(task, now, ATTEMPTS_REASON);
        }
        if (task.status() == AiTaskStatus.QUEUED) {
            // 它本来就该在队列里，只是消息丢了。状态、时间戳、尝试次数一律不动——
            // 写一次状态会推进 version，把并发的抢占执行权挤掉。
            return reenqueue(task);
        }
        return requeue(task, now);
    }

    /** {@code QUEUED} 任务的消息丢了：只补投递，不写库。 */
    private Action reenqueue(AiTask task) {
        queue.enqueue(task.taskId());
        LOGGER.info("恢复扫描补投丢失的队列消息：taskId={}", task.taskId());
        return Action.REQUEUED;
    }

    /** 执行者死了（心跳过期）：放回队列重跑。 */
    private Action requeue(AiTask task, OffsetDateTime now) {
        AiTask requeued = task.requeued(now);
        if (tasks.save(requeued).isEmpty()) {
            // 乐观锁冲突：另一个实例（或用户的一次取消）已经推进了这个任务。
            // 此时**不投递**——投出去的那个动作已经由赢家负责了。
            LOGGER.debug("恢复扫描放弃一个已被别人推进的任务：taskId={}", task.taskId());
            return Action.SKIPPED;
        }
        queue.enqueue(task.taskId());
        emitStatus(task.taskId(), AiTaskStatus.QUEUED);
        LOGGER.info(
                "恢复扫描把失去执行者的任务放回队列：taskId={} 原状态={} 尝试={}/{}",
                task.taskId(), task.status(), task.attemptNo(), task.maxAttempts());
        return Action.REQUEUED;
    }

    /** 兑现取消意图。 */
    private Action cancel(AiTask task, OffsetDateTime now) {
        AiTask canceled = task.canceled(now);
        if (tasks.save(canceled).isEmpty()) {
            return Action.SKIPPED;
        }
        emitStatus(task.taskId(), AiTaskStatus.CANCELED);
        emitDone(task.taskId(), AiTaskStatus.CANCELED);
        LOGGER.info("恢复扫描兑现取消意图：taskId={} 原状态={}", task.taskId(), task.status());
        return Action.CANCELED;
    }

    /**
     * 判超时。
     *
     * <p>{@code retryable=false}：这里判的是**恢复扫描**看到的超时，
     * 重投它只会再超时一次。而可重试的供应商超时由执行器自己处理
     * （{@link AiTaskExecutionService} 的 {@code RETRY_SCHEDULED}），
     * 那一条路径上任务根本没离开过执行者。
     */
    private Action timeOut(AiTask task, OffsetDateTime now, String reason) {
        AiTask timedOut = task.timedOut(reason, now);
        if (tasks.save(timedOut).isEmpty()) {
            return Action.SKIPPED;
        }
        events.append(
                task.taskId(),
                AiTaskEventType.ERROR,
                sequence -> AiTaskEventPayloads.error(
                        task.taskId(), TIMEOUT_CODE, reason, false, sequence));
        emitDone(task.taskId(), AiTaskStatus.TIMED_OUT);
        LOGGER.info("恢复扫描判定任务超时：taskId={} 原状态={} 原因={}",
                task.taskId(), task.status(), reason);
        return Action.TIMED_OUT;
    }

    private void emitStatus(long taskId, AiTaskStatus status) {
        events.append(
                taskId,
                AiTaskEventType.STATUS,
                sequence -> AiTaskEventPayloads.status(taskId, status, sequence));
    }

    private void emitDone(long taskId, AiTaskStatus finalStatus) {
        events.append(
                taskId,
                AiTaskEventType.DONE,
                sequence -> AiTaskEventPayloads.done(taskId, finalStatus, sequence));
    }
}
