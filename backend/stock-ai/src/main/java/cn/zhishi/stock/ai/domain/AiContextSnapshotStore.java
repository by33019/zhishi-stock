package cn.zhishi.stock.ai.domain;

import java.util.List;

/**
 * 任务上下文快照仓储（{@code ai_context_snapshot}）。
 *
 * <p>只写不读：本轮的读取方是 M3-08 的历史与证据接口。
 * 现在补一个 {@code findByTask} 只会有零个调用者，而"零调用者的方法"
 * 最容易在将来被误当成已实现的能力。
 *
 * <p>{@link #insertAll} 必须是**一次事务**：一份上下文要么完整落库，
 * 要么一条都没有。半份上下文比没有更糟——报告会引用一份不存在的快照。
 */
public interface AiContextSnapshotStore {

    void insertAll(long taskId, List<AiContextSnapshot> snapshots);

    /** 某任务已落库的快照条数（端到端核对与恢复判断用）。 */
    int countByTask(long taskId);
}
