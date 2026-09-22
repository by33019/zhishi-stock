package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiFeedbackStore;
import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStore;
import java.util.Optional;

/**
 * 报告查询（契约 §HIS-06）。
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
 */
public class AiReportQueryService {

    private final AiReportStore reports;
    private final AiTaskStore tasks;
    private final AiFeedbackStore feedbacks;

    public AiReportQueryService(AiReportStore reports, AiTaskStore tasks, AiFeedbackStore feedbacks) {
        this.reports = reports;
        this.tasks = tasks;
        this.feedbacks = feedbacks;
    }

    /** 取本人报告，含当前用户对它的反馈（未评价时为 {@code null}）。 */
    public AiReportDetail get(long reportId, long userId) {
        AiReport report = requireOwned(reportId, userId);
        AiFeedbackView feedback =
                feedbacks.find(reportId, userId).map(AiFeedbackView::from).orElse(null);
        return AiReportDetail.from(report, feedback);
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
