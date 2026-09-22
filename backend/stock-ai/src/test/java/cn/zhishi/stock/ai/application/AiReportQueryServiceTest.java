package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportQuality;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HIS-06 报告查询的业务规则。
 *
 * <h2>这里钉的是"三种拿不到必须长一样"</h2>
 * 报告不存在、报告属于别人、任务行缺失 —— 三者必须返回**逐字节相同**的业务码与文案。
 * 只要它们可区分，就等于向任何登录用户提供了一个探测他人报告 ID 是否存在的接口
 * （契约 §23.1）。因此断言的不是"抛了异常"，而是"三种情况的码与文案完全相等"。
 */
class AiReportQueryServiceTest {

    private static final long REPORT_ID = 8001L;
    private static final long TASK_ID = 7001L;
    private static final long SESSION_ID = 6001L;
    private static final long OWNER = 1001L;
    private static final long OTHER = 1002L;

    private static final OffsetDateTime CUTOFF =
            OffsetDateTime.parse("2026-09-22T15:00:00+08:00");
    private static final OffsetDateTime GENERATED =
            OffsetDateTime.parse("2026-09-22T15:02:00+08:00");

    private final AiReportStore reports = mock(AiReportStore.class);
    private final AiTaskStore tasks = mock(AiTaskStore.class);

    private final AiReportQueryService service = new AiReportQueryService(reports, tasks);

    @Test
    @DisplayName("本人报告：六章节、版本标识与数据截止时间逐项投影，ID 为字符串")
    void returnsOwnReport() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(limitedReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));

        AiReportDetail detail = service.get(REPORT_ID, OWNER);

        assertThat(detail.reportId()).isEqualTo(Long.toString(REPORT_ID));
        assertThat(detail.taskId()).isEqualTo(Long.toString(TASK_ID));
        assertThat(detail.sessionId()).isEqualTo(Long.toString(SESSION_ID));
        assertThat(detail.coreConclusion()).isEqualTo("核心结论正文");
        assertThat(detail.quoteEvidence()).isEqualTo("量价依据正文");
        assertThat(detail.comparisonAnalysis()).isNull();
        assertThat(detail.eventClues()).isNull();
        assertThat(detail.riskAndUncertainty()).isEqualTo("风险正文");
        assertThat(detail.disclaimer()).isEqualTo("本内容不构成投资建议。");
        assertThat(detail.renderedMarkdown()).isEqualTo("# 报告\n核心结论正文\n");
        assertThat(detail.qualityStatus()).isEqualTo("LIMITED");
        assertThat(detail.limited()).isTrue();
        assertThat(detail.limitedReason()).isEqualTo("分析区间内没有可用资讯");
        assertThat(detail.marketDataCutoffAt()).isEqualTo(CUTOFF);
        assertThat(detail.newsDataCutoffAt()).isNull();
        assertThat(detail.contentSchemaVersion()).isEqualTo("v1");
        assertThat(detail.promptVersion()).isEqualTo("p2");
        assertThat(detail.providerCode()).isEqualTo("DASHSCOPE");
        assertThat(detail.modelCode()).isEqualTo("qwen3.8-max-0902");
        assertThat(detail.generatedAt()).isEqualTo(GENERATED);
    }

    @Test
    @DisplayName("反馈恒为 null：当前不存在任何写入路径，这是事实而不是默认值")
    void feedbackIsAlwaysNullUntilFeedbackExists() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(limitedReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));

        assertThat(service.get(REPORT_ID, OWNER).feedback()).isNull();
    }

    @Test
    @DisplayName("报告不存在：404 业务码，且不再去查任务表")
    void missingReportIsNotFound() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(REPORT_ID, OWNER))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.REPORT_NOT_FOUND));
        verify(tasks, never()).find(anyLong());
    }

    @Test
    @DisplayName("他人的报告与不存在的报告返回完全相同的码与文案，无法据此探测 ID 是否存在")
    void otherUsersReportIsIndistinguishableFromMissing() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(limitedReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OTHER)));

        AiTaskException notMine = catchException(() -> service.get(REPORT_ID, OWNER));

        when(reports.find(REPORT_ID)).thenReturn(Optional.empty());
        AiTaskException missing = catchException(() -> service.get(REPORT_ID, OWNER));

        assertThat(notMine.code()).isEqualTo(missing.code());
        assertThat(notMine.getMessage()).isEqualTo(missing.getMessage());
        assertThat(notMine.code().httpStatus()).isEqualTo(404);
        assertThat(notMine.code().externalCode()).isEqualTo("AI_REPORT_NOT_FOUND");
    }

    @Test
    @DisplayName("报告存在但任务行缺失（数据不一致）：同样按不存在处理，不抛 NPE")
    void danglingReportIsNotFound() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(limitedReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.empty());

        AiTaskException exception = catchException(() -> service.get(REPORT_ID, OWNER));

        assertThat(exception.code()).isEqualTo(AiTaskErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("合法报告：limitedReason 为空、isLimited 为 false")
    void validReportCarriesNoLimitedReason() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));

        AiReportDetail detail = service.get(REPORT_ID, OWNER);

        assertThat(detail.limited()).isFalse();
        assertThat(detail.limitedReason()).isNull();
        assertThat(detail.qualityStatus()).isEqualTo("VALID");
        assertThat(detail.newsDataCutoffAt()).isEqualTo(CUTOFF);
    }

    private static AiTaskException catchException(Runnable call) {
        try {
            call.run();
        } catch (AiTaskException exception) {
            return exception;
        }
        throw new AssertionError("期望抛出 AiTaskException，但没有抛出");
    }

    private static AiTask task(long userId) {
        return new AiTask(
                TASK_ID,
                "req-8001",
                SESSION_ID,
                userId,
                null,
                AiScene.STOCK,
                "这只股票近期怎么样？",
                null,
                null,
                AiTaskStatus.COMPLETED,
                false,
                1,
                2,
                "DASHSCOPE",
                "qwen3.8-max-0902",
                "trace-8001",
                GENERATED,
                GENERATED,
                GENERATED,
                GENERATED,
                GENERATED,
                GENERATED,
                GENERATED,
                GENERATED,
                null,
                null,
                null,
                3,
                List.of());
    }

    private static AiReport limitedReport() {
        return report(AiReportQuality.LIMITED, "分析区间内没有可用资讯", null);
    }

    private static AiReport validReport() {
        return report(AiReportQuality.VALID, null, CUTOFF);
    }

    private static AiReport report(
            AiReportQuality quality, String limitedReason, OffsetDateTime newsCutoff) {
        return new AiReport(
                REPORT_ID,
                TASK_ID,
                SESSION_ID,
                5001L,
                "核心结论正文",
                "量价依据正文",
                null,
                null,
                "风险正文",
                "本内容不构成投资建议。",
                "# 报告\n核心结论正文\n",
                quality,
                limitedReason,
                "v1",
                "p2",
                "DASHSCOPE",
                "qwen3.8-max-0902",
                CUTOFF,
                newsCutoff,
                "a".repeat(64),
                GENERATED);
    }
}
