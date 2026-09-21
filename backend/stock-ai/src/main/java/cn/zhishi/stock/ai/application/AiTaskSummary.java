package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiContextTarget;
import cn.zhishi.stock.ai.domain.AiScene;
import cn.zhishi.stock.ai.domain.AiTask;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.AiTaskStatusMachine;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 任务摘要（契约 §4.4），AI-03 / AI-04 / AI-07 / AI-08 的响应体共用。
 *
 * <h2>ID 一律是字符串</h2>
 * 项目约定「业务 ID 用 Snowflake 字符串，前端全程 string」。这里在类型上就是
 * {@code String}，不是在序列化时靠注解转换——后者会让"某个响应漏了转换"
 * 变成一个只有前端才发现的缺陷（JS 的 number 装不下 19 位整数）。
 *
 * <h2>{@code error} 是对象而不是三个平铺字段</h2>
 * 契约 §4.4 要求它可空。平铺成 {@code errorCode}/{@code errorMessage}/{@code errorRetryable}
 * 会让"没有错误"与"有错误但字段为空"在响应里长得一样，
 * 前端不得不靠 {@code errorCode != null} 去猜。
 */
public record AiTaskSummary(
        String taskId,
        String sessionId,
        AiScene scene,
        AiTaskStatus status,
        List<AiContextTarget> targets,
        String question,
        String progressStage,
        OffsetDateTime createdAt,
        OffsetDateTime firstChunkAt,
        OffsetDateTime completedAt,
        String reportId,
        ErrorView error) {

    public AiTaskSummary {
        targets = List.copyOf(targets);
    }

    /** 失败摘要。{@code retryable} 直接来自状态机，不在这里重写一份判定。 */
    public record ErrorView(String category, String code, String message, boolean retryable) {
    }

    /**
     * 由聚合投影。
     *
     * <p>{@code targets} 由调用方传入而不是直接取 {@code task.targets()}：
     * 从库里读回来的目标只有 bigint 代理键与代码快照，对外标识要经
     * {@code *IdentityProvider} 还原。让两个入口（新建 / 查询）都走这一条路径，
     * 才能保证同一份任务在两种时机给出逐字段相同的响应——
     * 两条投影路径必然分叉，而分叉不会报错，只会让刷新前后的摘要不一样。
     *
     * <p>{@code reportId} 同样由调用方传入，且必须来自
     * {@code AiReportStore.findByTask(taskId)}：任务表里**没有**这一列，
     * 报告与任务的关联唯一地存在 {@code ai_report.task_id} 上。
     *
     * <p>只对**终态**填充 {@code error}：一个正在重试的任务上残留着上一次的错误摘要，
     * 会让前端在"运行中"的任务旁边显示一条失败信息。错误只在失败类终态才是事实。
     */
    public static AiTaskSummary from(AiTask task, List<AiContextTarget> targets, Long reportId) {
        return new AiTaskSummary(
                Long.toString(task.taskId()),
                Long.toString(task.sessionId()),
                task.scene(),
                task.status(),
                targets,
                task.question(),
                task.status().progressStage(),
                task.createdAt(),
                task.firstChunkAt(),
                task.completedAt(),
                reportId == null ? null : Long.toString(reportId),
                errorOf(task));
    }

    private static ErrorView errorOf(AiTask task) {
        if (task.errorCode() == null) {
            return null;
        }
        return new ErrorView(
                task.errorCategory(),
                task.errorCode(),
                task.errorMessage(),
                AiTaskStatusMachine.retryable(task.status()));
    }
}
