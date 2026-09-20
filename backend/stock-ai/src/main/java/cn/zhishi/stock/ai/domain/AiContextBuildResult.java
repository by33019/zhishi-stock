package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次上下文构建的结果：固化的快照 + 证据候选 + 降级说明。
 *
 * @param snapshots         全部上下文快照，{@code snapshotNo} 从 1 连续递增
 * @param evidenceCandidates 证据候选，{@code evidenceNo} 从 1 连续递增
 * @param limitations       降级说明；**为空表示没有降级**，不是"未知"
 * @param coreDataAvailable 核心行情是否齐备；{@code false} 时任务不应创建
 */
public record AiContextBuildResult(
        List<AiContextSnapshot> snapshots,
        List<AiEvidenceCandidate> evidenceCandidates,
        List<String> limitations,
        boolean coreDataAvailable) {

    public AiContextBuildResult {
        snapshots = List.copyOf(snapshots);
        evidenceCandidates = List.copyOf(evidenceCandidates);
        limitations = List.copyOf(limitations);
    }

    /**
     * 各类数据的截止时间。
     *
     * <p>同一类别可能有多条快照（如同一批次里多只证券）。它们的截止时间本应相同
     * （同一批次），但真出现不一致时取**最早**的那个：
     * "报告基于的数据不晚于 X"这句话因此仍然成立。取最晚会让报告显得比实际更新。
     *
     * <p>返回顺序固定为 {@link AiContextType} 的声明顺序，不随快照顺序漂移——
     * 否则同一份数据在两次构建里会给出不同顺序的数组，而前端会据此渲染出跳动的列表。
     */
    public List<AiDataCutoff> dataCutoffs() {
        Map<AiContextType, OffsetDateTime> earliest = new LinkedHashMap<>();
        for (AiContextSnapshot snapshot : snapshots) {
            if (snapshot.dataCutoffAt() == null) {
                continue;
            }
            earliest.merge(
                    snapshot.contextType(),
                    snapshot.dataCutoffAt(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }
        List<AiDataCutoff> cutoffs = new ArrayList<>();
        for (AiContextType type : AiContextType.values()) {
            OffsetDateTime value = earliest.get(type);
            if (value != null) {
                cutoffs.add(new AiDataCutoff(type, value));
            }
        }
        return List.copyOf(cutoffs);
    }

    /** 实际取到的数据类别，顺序同 {@link AiContextType}。 */
    public List<AiContextType> availableCategories() {
        return dataCutoffs().stream().map(AiDataCutoff::category).toList();
    }
}
