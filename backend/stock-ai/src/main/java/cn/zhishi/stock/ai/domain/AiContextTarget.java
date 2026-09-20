package cn.zhishi.stock.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 已解析的任务目标（AI-02 的 {@code targets} 元素，也是上下文构建的输入）。
 *
 * <p>"已解析"指 {@code targetId} 已能对应到真实主数据，且 {@code targetCode} /
 * {@code targetName} 是从主数据取到的快照。解析发生在用例层（{@code AiContextPreviewService}），
 * 因为它要区分"客户端传了不存在的标的"（400）与"标的没有数据"（降级）。
 *
 * <p>{@code storageId} 是内部代理键（{@code ai_task_target.target_id} 是 bigint），
 * 标 {@link JsonIgnore} 保证它**不会**出现在响应里——返回 bigint 会让前端拼出的跳转链接 404
 * （M2-06 / M2-11 已各踩过一次）。
 *
 * @param targetType 目标类型
 * @param targetId   对外标识：{@code sim-600519} / {@code sim-bk0001} / {@code CN}
 * @param targetCode 目标代码快照
 * @param targetName 目标名称快照
 * @param targetRole 目标角色
 * @param storageId  内部代理键
 */
public record AiContextTarget(
        AiTargetType targetType,
        String targetId,
        String targetCode,
        String targetName,
        AiTargetRole targetRole,
        @JsonIgnore Long storageId) {
}
