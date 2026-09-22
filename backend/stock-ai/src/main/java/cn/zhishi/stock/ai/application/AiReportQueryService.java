package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportStore;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStore;

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

    public AiReportQueryService(AiReportStore reports, AiTaskStore tasks) {
        this.reports = reports;
        this.tasks = tasks;
    }

    /** 取本人报告。{@code reportId} 是库内 bigint 主键（对外由控制器转字符串）。 */
    public AiReportDetail get(long reportId, long userId) {
        AiReport report = reports.find(reportId).orElseThrow(() -> AiTaskException.reportNotFound(reportId));
        AiTask task = tasks.find(report.taskId())
                .orElseThrow(() -> AiTaskException.reportNotFound(reportId));
        if (task.userId() != userId) {
            throw AiTaskException.reportNotFound(reportId);
        }
        return AiReportDetail.from(report);
    }
}
