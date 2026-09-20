package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 任务冻结上下文的一条快照，字段与 {@code ai_context_snapshot} 对齐。
 *
 * <p>"冻结"是这个词的全部要点：上下文在**任务开始时**取一次并固化，
 * 之后行情再怎么变，这份报告引用的仍是固化时看到的事实。
 * 不冻结的话，一份 30 秒后才生成完的报告会引用"生成时刻"的行情，
 * 而正文里写的却是"分析区间内"——两者对不上，且不会报错。
 *
 * @param snapshotNo          任务内稳定顺序，从 1 开始（{@code ck_ai_context_snapshot_no}）
 * @param contextType         数据类别
 * @param sourceObjectType    内部来源对象类型（如 {@code SECURITY} / {@code SECTOR} / {@code NEWS}）
 * @param sourceObjectId      内部来源对象 ID；无内部 ID 时为 {@code null}
 * @param sourceKey           无内部 ID 时使用的稳定来源键（如 {@code CN}）
 * @param dataTime            该条事实对应的数据时间
 * @param dataCutoffAt        任务固化该类数据的截止时间
 * @param contentHash         内容哈希，用于判定重复固化与引用一致性
 * @param contextData         去敏、清洗后的结构化上下文
 * @param isEvidenceCandidate 是否可作为报告证据候选
 */
public record AiContextSnapshot(
        int snapshotNo,
        AiContextType contextType,
        String sourceObjectType,
        Long sourceObjectId,
        String sourceKey,
        OffsetDateTime dataTime,
        OffsetDateTime dataCutoffAt,
        String contentHash,
        Map<String, Object> contextData,
        boolean isEvidenceCandidate) {

    public AiContextSnapshot {
        if (snapshotNo < 1) {
            throw new IllegalArgumentException("snapshotNo 从 1 开始：" + snapshotNo);
        }
        contextData = Map.copyOf(contextData);
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey 不得为空");
        }
        if (dataCutoffAt == null) {
            throw new IllegalArgumentException("dataCutoffAt 不得为空——它就是「冻结」的时间锚点");
        }
    }
}
