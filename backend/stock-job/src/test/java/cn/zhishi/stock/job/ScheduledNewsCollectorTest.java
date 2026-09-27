package cn.zhishi.stock.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.news.application.NewsIngestionService;
import cn.zhishi.stock.news.domain.NewsIngestionResult;
import cn.zhishi.stock.system.job.JobExecutionRecorder;
import cn.zhishi.stock.system.job.JobExecutionRequest;
import cn.zhishi.stock.system.job.JobNames;
import cn.zhishi.stock.system.job.JobTriggerType;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledNewsCollectorTest {

    /** 固定时钟，钉在 Asia/Shanghai 的 2026-09-20 20:13:14（CI 是 UTC，不能靠系统时区）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-20T12:13:14Z"), ZoneId.of("Asia/Shanghai"));

    private static final NewsIngestionResult NOTHING_FETCHED =
            new NewsIngestionResult(0, 0, 0, 0, 0, List.of());

    private final NewsIngestionService ingestion = mock(NewsIngestionService.class);
    private final JobExecutionRecorder executions = mock(JobExecutionRecorder.class);

    private final ScheduledNewsCollector collector =
            new ScheduledNewsCollector(ingestion, executions, CLOCK);

    /**
     * 让记录器**就地执行**它收到的动作。
     *
     * <p>这里刻意不 mock 掉动作本身：本类要验的是"采集失败时是否正确留痕"，
     * 而那个逻辑住在动作里。若把 {@code record} 整个桩成空实现，
     * 下面所有关于失败的用例都会变成"没跑过"却仍然变绿。
     */
    @BeforeEach
    void runTheActionInline() {
        when(ingestion.ingest(null)).thenReturn(NOTHING_FETCHED);
        when(executions.record(any(), any())).thenAnswer(
                invocation -> invocation.<JobExecutionRecorder.JobAction>getArgument(1).run());
    }

    /**
     * 不设下界：增量判据由"来源 ID 幂等"承担。
     *
     * <p>把游标交给 Provider 意味着**两个模块各自维护一份进度**，
     * 而重投的稿件本就会被 {@code uk_stock_news_source_content} 挡住。
     */
    @Test
    void collectsWithoutALowerBound() {
        collector.collect();

        verify(ingestion).ingest(null);
    }

    /**
     * 每轮都要留下一条 {@code SCHEDULED} 执行记录。
     *
     * <p>任务名必须取自 {@code JobNames}（与后台白名单同一份常量）：
     * 两处各写字面量时，"后台执行历史页看不到定时任务"会成为一个没有任何报错的故障。
     */
    @Test
    void recordsAScheduledExecutionUnderTheSharedJobName() {
        collector.collect();

        ArgumentCaptor<JobExecutionRequest> request =
                ArgumentCaptor.forClass(JobExecutionRequest.class);
        verify(executions).record(request.capture(), any());
        assertThat(request.getValue().jobName()).isEqualTo(JobNames.NEWS_INGEST);
        assertThat(request.getValue().handlerName()).isEqualTo(JobNames.NEWS_INGEST_HANDLER);
        assertThat(request.getValue().triggerType()).isEqualTo(JobTriggerType.SCHEDULED);
        assertThat(request.getValue().batchId()).isNotBlank();
        assertThat(request.getValue().traceId()).startsWith("sched-");
    }

    @Test
    void writesNoFailureMarkWhenCollectionSucceeds() {
        collector.collect();

        verify(ingestion, never()).recordSyncFailure(any());
    }

    /**
     * 失败留痕必须发生在采集事务回滚**之后**：{@code recordSyncFailure} 标了
     * {@code REQUIRES_NEW}，只有在 {@code ingest} 抛出之后调用才会落到新事务里；
     * 一旦有人在 {@code ingest} 内部或同一个事务里写，失败标记会跟着回滚，
     * 症状是"每次失败都不留痕迹"，而 NEWS-03 永远报 OK。
     */
    @Test
    void recordsTheFailureAtTheCurrentInstant() {
        when(ingestion.ingest(null)).thenThrow(new IllegalStateException("取数失败"));

        catchThrowable(collector::collect);

        ArgumentCaptor<OffsetDateTime> at = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(ingestion).recordSyncFailure(at.capture());
        assertThat(at.getValue()).isEqualTo(OffsetDateTime.parse("2026-09-20T20:13:14+08:00"));
    }

    /**
     * 失败要向上抛：{@code @Scheduled} 的 fixed-delay 任务在异常时会被
     * {@code LOG_AND_SUPPRESS_ERROR_HANDLER} 记一条 ERROR 并继续下一轮，
     * 所以"抛出去"不会中断调度，却能让运维在日志里看到——只写库不抛日志是哑的。
     */
    @Test
    void letsTheFailurePropagateSoTheSchedulerLogsIt() {
        when(ingestion.ingest(null)).thenThrow(new IllegalStateException("取数失败"));

        Throwable thrown = catchThrowable(collector::collect);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("取数失败");
    }

    /**
     * 写失败标记本身失败时，不能把原始失败顶掉。
     *
     * <p>两个失败最常见的同时出现方式就是"数据库不可用"——那也正是采集失败的原因。
     * 若直接让标记的异常覆盖原始异常，日志里只剩一个次要错误，排查方向被带偏。
     */
    @Test
    void keepsTheOriginalFailureWhenTheFailureMarkItselfFails() {
        when(ingestion.ingest(null)).thenThrow(new IllegalStateException("取数失败"));
        doThrow(new IllegalStateException("数据库不可用"))
                .when(ingestion)
                .recordSyncFailure(any());

        Throwable thrown = catchThrowable(collector::collect);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("取数失败");
        assertThat(thrown.getSuppressed())
                .extracting(Throwable::getMessage)
                .containsExactly("数据库不可用");
    }
}
