package cn.zhishi.stock.system.job;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把"执行一件事"包成"写一行开始记录 → 执行 → 写回终态"。
 *
 * <h2>为什么由它决定记录，而不是让每个执行体自己写两次库</h2>
 * 定时采集器、人工触发、重试三条路径都要写同一张表。各写各的会带来两个必然后果：
 * 有一条路径忘了在异常时回填终态（那行记录永远停在 {@code RUNNING}，
 * 而"卡住的任务"正是运维第一个要查的东西），以及三条路径的字段口径慢慢分叉。
 *
 * <h2>异常照原样抛出去</h2>
 * 记录失败之后必须重抛。定时任务靠异常冒到 Spring 的调度器才会留下 ERROR 日志；
 * 人工触发的 202 响应已经发出去了，但服务端的日志仍然需要这条异常。
 * 吞掉它等于把"这次为什么失败"从日志里删掉，只留在数据库的一行摘要里。
 *
 * <h2>开始记录的写入失败会中断执行</h2>
 * 与"审计写失败不阻断业务"（{@code JdbcSysLogAuditLog}）相反，这里让异常冒出去。
 * 区别在于：审计是**旁路事实**，它挂了业务仍然成立；而执行记录的插入是这个用例的
 * 第一步，写不进去说明数据库有问题，此时继续跑采集只会让异常以更难归因的形式出现。
 */
public class JobExecutionRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobExecutionRecorder.class);

    /** {@code error_summary} 是 varchar(1000)，严格模式下超长插入会直接报错。 */
    private static final int SUMMARY_LIMIT = 1000;
    private static final int CODE_LIMIT = 64;

    /** 未包装异常的统一类别：调用方没说明这是什么类型的失败。 */
    private static final String UNEXPECTED_CATEGORY = "UNEXPECTED";

    private final JobExecutionStore store;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public JobExecutionRecorder(
            JobExecutionStore store, LongSupplier idGenerator, Clock clock) {
        this.store = store;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** 要执行的一件事。返回结论，或抛异常。 */
    @FunctionalInterface
    public interface JobAction {
        JobExecutionOutcome run();
    }

    /**
     * 只写下 {@code RUNNING} 记录，返回执行 ID。供"先落库、再异步执行"的调用方使用。
     *
     * <p>与 {@link #record} 拆开，是因为人工触发要求先返回 202 再执行，
     * 而响应里必须带上真实的执行 ID——那就必须先把记录写进库。
     */
    public long start(JobExecutionRequest request) {
        long executionId = idGenerator.getAsLong();
        store.insert(new NewJobExecution(executionId, request, OffsetDateTime.now(clock)));
        return executionId;
    }

    /**
     * 执行并把结论回填到已存在的记录上（异常路径同样回填，然后原样抛出）。
     *
     * <p>回填用的 {@code store.complete} 带 {@code status = 'RUNNING'} 条件，
     * 因此"这条记录已经被别人收尾过"不会让本次结论覆盖它。
     */
    public JobExecutionOutcome finish(long executionId, JobAction action) {
        JobExecutionOutcome outcome;
        try {
            outcome = action.run();
        } catch (RuntimeException exception) {
            complete(executionId, failureOf(exception));
            throw exception;
        }
        complete(executionId, outcome);
        return outcome;
    }

    /**
     * 记录并执行一次任务（同步路径：定时任务用这一条）。
     *
     * @return 执行体的结论（异常路径不会返回，而是把异常抛出去）
     */
    public JobExecutionOutcome record(JobExecutionRequest request, JobAction action) {
        return finish(start(request), action);
    }

    private void complete(long executionId, JobExecutionOutcome outcome) {
        boolean written = store.complete(executionId, outcome, OffsetDateTime.now(clock));
        if (!written) {
            // 不抛异常：执行已经发生，把"记录没写回"升级成业务失败只会让调用方
            // 以为任务没跑。但必须留下痕迹——这条被漏记的执行在页面上会永远停在 RUNNING。
            LOGGER.warn("任务执行记录未被回填：executionId={}，status={}", executionId, outcome.status());
        }
    }

    private static JobExecutionOutcome failureOf(RuntimeException exception) {
        if (exception instanceof JobExecutionFailure failure) {
            return JobExecutionOutcome.failed(
                    failure.errorCategory(),
                    truncate(failure.errorCode(), CODE_LIMIT),
                    truncate(failure.safeSummary(), SUMMARY_LIMIT));
        }
        return JobExecutionOutcome.failed(
                UNEXPECTED_CATEGORY,
                truncate(exception.getClass().getSimpleName(), CODE_LIMIT),
                truncate(exception.getMessage(), SUMMARY_LIMIT));
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }
}
