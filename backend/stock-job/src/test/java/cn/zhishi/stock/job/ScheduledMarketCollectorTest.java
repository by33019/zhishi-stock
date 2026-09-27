package cn.zhishi.stock.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.market.application.MarketIngestionService;
import cn.zhishi.stock.system.job.JobExecutionRecorder;
import cn.zhishi.stock.system.job.JobExecutionRequest;
import cn.zhishi.stock.system.job.JobNames;
import cn.zhishi.stock.system.job.JobTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledMarketCollectorTest {

    private final MarketIngestionService ingestion = mock(MarketIngestionService.class);
    private final JobExecutionRecorder executions = mock(JobExecutionRecorder.class);

    @BeforeEach
    void runTheActionInline() {
        when(executions.record(any(), any())).thenAnswer(
                invocation -> invocation.<JobExecutionRecorder.JobAction>getArgument(1).run());
    }

    @Test
    void triggersTheSameMarketIngestionUseCase() {
        new ScheduledMarketCollector(ingestion, executions).collect();

        verify(ingestion).collect("CN");
    }

    /**
     * 任务名取自 {@code JobNames}（与后台白名单同一份常量）。
     *
     * <p>后台执行历史页按 {@code job_name} 查询，两处字面量差一个字符就会让
     * "定时任务的记录一条都看不到"——而且不会有任何报错，SQL 照样成功。
     */
    @Test
    void recordsAScheduledExecutionUnderTheSharedJobName() {
        new ScheduledMarketCollector(ingestion, executions).collect();

        ArgumentCaptor<JobExecutionRequest> request =
                ArgumentCaptor.forClass(JobExecutionRequest.class);
        verify(executions).record(request.capture(), any());
        assertThat(request.getValue().jobName()).isEqualTo(JobNames.MARKET_OVERVIEW_COLLECT);
        assertThat(request.getValue().handlerName())
                .isEqualTo(JobNames.MARKET_OVERVIEW_HANDLER);
        assertThat(request.getValue().triggerType()).isEqualTo(JobTriggerType.SCHEDULED);
        assertThat(request.getValue().traceId()).startsWith("sched-");
    }
}
