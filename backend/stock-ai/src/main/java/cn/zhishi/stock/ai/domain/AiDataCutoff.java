package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;

/**
 * 某一类上下文的截止时间（AI-02 的 {@code dataCutoffAt} 元素）。
 *
 * <p>{@code dataCutoffAt} 是**该类数据在任务开始时固化的时间点**，不是"数据有多新"的估计。
 * 它必须是取数时实际观察到的时刻（行情批次的 {@code dataTime}、资讯同步的最后成功时间），
 * 不允许用"现在"填充——"现在"会让报告看起来比实际更新。
 *
 * @param category 数据类别
 * @param dataCutoffAt 该类数据的截止时间
 */
public record AiDataCutoff(AiContextType category, OffsetDateTime dataCutoffAt) {
}
