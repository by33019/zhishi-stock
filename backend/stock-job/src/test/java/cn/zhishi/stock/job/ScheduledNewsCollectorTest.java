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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledNewsCollectorTest {

    /** 固定时钟，钉在 Asia/Shanghai 的 2026-09-20 20:13:14（CI 是 UTC，不能靠系统时区）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-20T12:13:14Z"), ZoneId.of("Asia/Shanghai"));

    private final NewsIngestionService ingestion = mock(NewsIngestionService.class);

    private final ScheduledNewsCollector collector = new ScheduledNewsCollector(ingestion, CLOCK);

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
