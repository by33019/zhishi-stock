package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 后台 AI 任务的列表条目（契约 §19 ADM-AI-02）。
 *
 * <h2>只有元数据，没有正文</h2>
 * 契约原文："默认不返回用户问题、消息正文、完整上下文和报告正文"。这条边界
 * 由本记录的**形状**保证——它没有能装正文的字段，列表 SQL 也不碰
 * {@code ai_message} / {@code ai_report}。读者问题属于敏感内容，
 * 读取需要单独的合规授权（MVP 不提供）。
 *
 * <p>目标摘要来自 {@code ai_task_target} 的快照列（{@code target_code} /
 * {@code target_name}），是**任务创建时刻**的事实，不做实时解析。
 */
public record AdminAiTaskSummary(
        long taskId,
        long sessionId,
        long userId,
        String scene,
        AiTaskStatus status,
        String providerCode,
        String modelCode,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        LlmErrorCategory errorCategory,
        String traceId,
        List<AdminAiTaskTargetSummary> targets) {

    public AdminAiTaskSummary {
        targets = List.copyOf(targets);
    }
}
