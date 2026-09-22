package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiEvidenceStore;
import cn.zhishi.stock.ai.domain.AiEvidenceType;
import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 报告与来源引用的查询（契约 §HIS-06 / §HIS-07）。
 *
 * <h2>为什么归属校验要走任务表，而不是报告表</h2>
 * {@code ai_report} 里**没有** {@code user_id}：报告属于谁由 {@code ai_report.task_id →
 * ai_task.user_id} 决定。这不是可以省的两次查询——把 {@code user_id} 冗余进报告表
 * 会多出一份"谁是主人"的定义，而两份定义分叉时，泄露的是别人的研究结论。
 *
 * <h2>三种"拿不到"共用同一个 404</h2>
 * 报告不存在、报告属于别人、任务失败因而没有报告（契约 §HIS-06 明写"失败任务不存在报告资源"）
 * ——三者返回同一句 {@code AI_REPORT_NOT_FOUND}。可区分它们就等于提供了一个
 * 探测他人报告 ID 是否有效的接口（契约 §23.1）。
 *
 * <h2>HIS-07 与 HIS-06 在同一个用例里</h2>
 * 报告与它的来源引用是同一个资源的两个视图（{@code /ai/reports/{id}} 与
 * {@code /ai/reports/{id}/evidence}），共用一条归属判据。分成两个服务会让那条判据
 * 有两个实现，而分叉的表现是越权而不是报错。
 */
public class AiReportQueryService {

    private final AiReportStore reports;
    private final AiTaskStore tasks;
    private final AiFeedbackStore feedbacks;
    private final AiEvidenceStore evidences;

    public AiReportQueryService(
            AiReportStore reports,
            AiTaskStore tasks,
            AiFeedbackStore feedbacks,
            AiEvidenceStore evidences) {
        this.reports = reports;
        this.tasks = tasks;
        this.feedbacks = feedbacks;
        this.evidences = evidences;
    }

    /** 取本人报告，含当前用户对它的反馈（未评价时为 {@code null}）。 */
    public AiReportDetail get(long reportId, long userId) {
        AiReport report = requireOwned(reportId, userId);
        AiFeedbackView feedback =
                feedbacks.find(reportId, userId).map(AiFeedbackView::from).orElse(null);
        return AiReportDetail.from(report, feedback);
    }

    /**
     * 取本人报告引用的来源（契约 §HIS-07），按 {@code evidenceNo} 升序。
     *
     * <h2>归属判据复用 {@link #requireOwned}</h2>
     * 证据挂在报告下，所以"能读这份证据"与"能读这份报告"必须是同一条判据。
     * 各写一份时两条判据会分叉，而分叉的表现是**能读到别人报告的来源列表**
     * ——一个不会报错的越权（同 {@code AiFeedbackService} 的处置）。
     *
     * <h2>按类型过滤在内存里做</h2>
     * 一份报告的证据是那次定稿的**全集**（个位数行），一次取回再过滤与
     * 多走一次带 {@code WHERE} 的查询没有可测量的差别；而给仓储加一个带条件的
     * {@code listByReportAndType} 会让"证据全集"这个概念出现两个入口。
     *
     * <h2>空结果不是 404</h2>
     * 报告存在但没有证据行（如候选集为空的分析）返回空数组。契约 §HIS-07 的
     * 说明是"不能静默删除编号"——没有编号与查不到编号是两件事，
     * 后者由 {@code requireOwned} 抛 {@code AI_REPORT_NOT_FOUND}。
     *
     * @param evidenceTypeCode 可空；非空且不在 {@code AiEvidenceType} 取值内时抛 400
     */
    public List<AiEvidenceView> listEvidence(long reportId, long userId, String evidenceTypeCode) {
        AiReport report = requireOwned(reportId, userId);
        AiEvidenceType type = evidenceTypeOf(evidenceTypeCode);
        return evidences.listByReport(report.reportId()).stream()
                .filter(evidence -> type == null || evidence.evidenceType() == type)
                .map(AiEvidenceView::from)
                .toList();
    }

    /**
     * 解析可选的证据类型过滤值。
     *
     * <p>大小写不敏感（同 {@code AiHistoryService.sceneOf}）：前端拼查询串时
     * 大小写是它自己的事，而"NEWS 与 news 只差一个字母却一个 400 一个 200"
     * 是纯粹的浪费。空白视同"不过滤"而不是报错——空参数在查询串里很常见。
     */
    private static AiEvidenceType evidenceTypeOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (AiEvidenceType type : AiEvidenceType.values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new InvalidAiEvidenceQueryException("不支持的证据类型：" + code);
    }

    /**
     * 归属校验后的报告。
     *
     * <p>公开出来是给**反馈**的写路径复用（{@code AiFeedbackService}）：能写反馈的前提与
     * 能读报告的前提必须是同一条判据。各写一份时两条判据会分叉，而分叉的表现是
     * "读不到的报告却能给它打分"——一个不会报错的越权。
     *
     * @throws AiTaskException 不存在、不属于本人、或任务行缺失（三种返回同一个码）
     */
    public AiReport requireOwned(long reportId, long userId) {
        AiReport report = reports.find(reportId)
                .orElseThrow(() -> AiTaskException.reportNotFound(reportId));
        Optional<AiTask> task = tasks.find(report.taskId());
        if (task.isEmpty() || task.get().userId() != userId) {
            throw AiTaskException.reportNotFound(reportId);
        }
        return report;
    }
}
