package cn.zhishi.stock.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zhishi.stock.ai.domain.AiEvidence;
import cn.zhishi.stock.ai.domain.AiEvidenceAccessStatus;
import cn.zhishi.stock.ai.domain.AiEvidenceStore;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import cn.zhishi.stock.ai.domain.AiFeedback;
import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import cn.zhishi.stock.ai.domain.AiFeedbackType;
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
 * HIS-06 报告查询与 HIS-07 来源引用的业务规则。
 *
 * <h2>这里钉的是"三种拿不到必须长一样"</h2>
 * 报告不存在、报告属于别人、任务行缺失 —— 三者必须返回**逐字节相同**的业务码与文案。
 * 只要它们可区分，就等于向任何登录用户提供了一个探测他人报告 ID 是否存在的接口
 * （契约 §23.1）。因此断言的不是"抛了异常"，而是"三种情况的码与文案完全相等"。
 *
 * <h2>HIS-07 的两条出口规则</h2>
 * 一是授权受限（{@code RESTRICTED}）不给原文地址、但摘要必须留；
 * 二是非白名单协议的链接不外发、而访问状态不因此改变。
 * 两条都是"少给一个字段"，很容易在重构里被抹平成"原样透传"——所以各有一个用例钉住。
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
    private final AiFeedbackStore feedbacks = mock(AiFeedbackStore.class);
    private final AiEvidenceStore evidences = mock(AiEvidenceStore.class);

    private final AiReportQueryService service =
            new AiReportQueryService(reports, tasks, feedbacks, evidences);

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
    @DisplayName("未评价时 feedback 为 null：区分「还没有人评价」与「评价结果是中性」")
    void feedbackIsNullWhenNotYetRated() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(limitedReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));

        assertThat(service.get(REPORT_ID, OWNER).feedback()).isNull();
    }

    @Test
    @DisplayName("已评价时报告详情一次带回反馈，不需要前端再发一次请求")
    void reportDetailCarriesOwnFeedback() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(limitedReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));
        when(feedbacks.find(REPORT_ID, OWNER)).thenReturn(Optional.of(new AiFeedback(
                9001L, REPORT_ID, OWNER, AiFeedbackType.NOT_HELPFUL, null, "结论缺少区间数据", GENERATED, GENERATED)));

        var feedback = service.get(REPORT_ID, OWNER).feedback();

        assertThat(feedback).isNotNull();
        assertThat(feedback.feedbackId()).isEqualTo("9001");
        assertThat(feedback.feedbackType()).isEqualTo("NOT_HELPFUL");
        assertThat(feedback.detail()).isEqualTo("结论缺少区间数据");
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

    // ---------- HIS-07 来源引用 ----------

    @Test
    @DisplayName("来源引用：契约的 8 个字段逐项投影，内部代理键与哈希不外发")
    void returnsOwnEvidence() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));
        when(evidences.listByReport(REPORT_ID)).thenReturn(List.of(
                evidence(1, AiEvidenceType.QUOTE, null, AiEvidenceAccessStatus.AVAILABLE),
                evidence(2, AiEvidenceType.NEWS, "https://news.example.com/a", AiEvidenceAccessStatus.AVAILABLE)));

        List<AiEvidenceView> views = service.listEvidence(REPORT_ID, OWNER, null);

        assertThat(views).hasSize(2);
        AiEvidenceView quote = views.get(0);
        assertThat(quote.evidenceNo()).isEqualTo(1);
        assertThat(quote.evidenceType()).isEqualTo("QUOTE");
        assertThat(quote.sourceTitle()).isEqualTo("来源标题1");
        assertThat(quote.sourceUrl()).isNull();
        assertThat(quote.evidenceSummary()).isEqualTo("来源摘要1");
        assertThat(quote.sourcePublishedAt()).isEqualTo(CUTOFF);
        assertThat(quote.dataTime()).isEqualTo(CUTOFF);
        assertThat(quote.accessStatus()).isEqualTo("AVAILABLE");
        assertThat(views.get(1).sourceUrl())
                .describedAs("https 在白名单内，应当原样给出")
                .isEqualTo("https://news.example.com/a");
    }

    @Test
    @DisplayName("授权受限：保留摘要但**不给原文地址**（RESTRICTED 的定义）")
    void restrictedEvidenceHidesUrlButKeepsSummary() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));
        when(evidences.listByReport(REPORT_ID)).thenReturn(List.of(
                evidence(1, AiEvidenceType.NEWS, "https://paid.example.com/a", AiEvidenceAccessStatus.RESTRICTED)));

        AiEvidenceView view = service.listEvidence(REPORT_ID, OWNER, null).get(0);

        assertThat(view.sourceUrl()).isNull();
        assertThat(view.evidenceSummary())
                .describedAs("受限不等于没有证据：摘要必须还在")
                .isEqualTo("来源摘要1");
        assertThat(view.accessStatus()).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("非白名单协议的链接一律不外发，但状态仍是 AVAILABLE")
    void nonWhitelistedSchemeIsNotExposed() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));
        when(evidences.listByReport(REPORT_ID)).thenReturn(List.of(
                evidence(1, AiEvidenceType.NEWS, "http://news.example.com/a", AiEvidenceAccessStatus.AVAILABLE),
                evidence(2, AiEvidenceType.NEWS, "javascript:alert(1)", AiEvidenceAccessStatus.AVAILABLE)));

        List<AiEvidenceView> views = service.listEvidence(REPORT_ID, OWNER, null);

        assertThat(views).extracting(AiEvidenceView::sourceUrl).containsOnlyNulls();
        assertThat(views).extracting(AiEvidenceView::accessStatus)
                .describedAs("链接被拦下不代表来源失效，状态不能跟着改")
                .containsOnly("AVAILABLE");
    }

    @Test
    @DisplayName("按类型过滤：大小写不敏感，其它类型不出现")
    void filtersByEvidenceType() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));
        when(evidences.listByReport(REPORT_ID)).thenReturn(List.of(
                evidence(1, AiEvidenceType.QUOTE, null, AiEvidenceAccessStatus.AVAILABLE),
                evidence(2, AiEvidenceType.NEWS, null, AiEvidenceAccessStatus.AVAILABLE)));

        assertThat(service.listEvidence(REPORT_ID, OWNER, "news"))
                .extracting(AiEvidenceView::evidenceType)
                .containsExactly("NEWS");
        assertThat(service.listEvidence(REPORT_ID, OWNER, "  "))
                .describedAs("空白视同不过滤，而不是报错")
                .hasSize(2);
    }

    @Test
    @DisplayName("证据类型不在白名单：400，且文案落在「证据类型」上")
    void rejectsUnknownEvidenceType() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));

        assertThatThrownBy(() -> service.listEvidence(REPORT_ID, OWNER, "NEWW"))
                .isInstanceOf(InvalidAiEvidenceQueryException.class)
                .hasMessageContaining("不支持的证据类型");
    }

    @Test
    @DisplayName("他人的报告：引用查询与报告查询返回同一个 404，且不去查证据表")
    void otherUsersReportHasNoEvidence() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OTHER)));

        assertThatThrownBy(() -> service.listEvidence(REPORT_ID, OWNER, null))
                .isInstanceOf(AiTaskException.class)
                .satisfies(exception -> assertThat(((AiTaskException) exception).code())
                        .isEqualTo(AiTaskErrorCode.REPORT_NOT_FOUND));
        verify(evidences, never()).listByReport(anyLong());
    }

    @Test
    @DisplayName("报告存在但没有引用：返回空列表而不是 404（与「查不到报告」区分开）")
    void reportWithoutEvidenceIsNotNotFound() {
        when(reports.find(REPORT_ID)).thenReturn(Optional.of(validReport()));
        when(tasks.find(TASK_ID)).thenReturn(Optional.of(task(OWNER)));
        when(evidences.listByReport(REPORT_ID)).thenReturn(List.of());

        assertThat(service.listEvidence(REPORT_ID, OWNER, null)).isEmpty();
    }

    private static AiEvidence evidence(
            int evidenceNo, AiEvidenceType type, String sourceUrl, AiEvidenceAccessStatus status) {
        return new AiEvidence(
                9000L + evidenceNo,
                REPORT_ID,
                evidenceNo,
                null,
                type,
                "SECURITY",
                600519L,
                "来源标题" + evidenceNo,
                sourceUrl,
                "来源摘要" + evidenceNo,
                CUTOFF,
                CUTOFF,
                status,
                "hash-" + evidenceNo,
                GENERATED);
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
