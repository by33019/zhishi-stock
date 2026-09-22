package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 会话仓储（{@code ai_session}）。
 *
 * <p>写入侧只需要"建、取、更新最后任务与活动时间"三件事。读取侧随 HIS-01 到来而长出
 * 列表与筛选——本轮落到 M3-08 才加，正是为了不让它被一个还没定的需求牵着走
 * （契约 §HIS-01 的筛选参数与排序口径当时尚未冻结）。
 *
 * <p>HIS-03 / HIS-04（改名、收藏、软删）所需的 {@code If-Match} 版本推进
 * **仍然没有**：它们是写路径上的并发控制，与读取路径的筛选不是同一件事，另行排期。
 */
public interface AiSessionStore {

    Optional<AiSession> find(long sessionId);

    void insert(AiSession session);

    /**
     * 记录会话最近一次任务活动。
     *
     * <p>不做版本校验：它改的是"最后活动时间"这类派生事实，不是用户可见内容。
     * 并发下后写的赢没有危害，而为此引入 {@code If-Match} 只会让创建任务多一个失败点。
     */
    void touch(long sessionId, long lastTaskId, OffsetDateTime at);

    /**
     * 本人会话历史列表（契约 §HIS-01），按最后活动时间倒序。
     *
     * <p>软删除（{@code DELETED}）的会话不在结果里——契约 §HIS-02 要求"删除状态对普通列表不可见"。
     * 这里不做分页越界的钳制：越界由用例层判，仓储只认 offset/limit。
     *
     * @param offset 从 0 开始
     * @param limit  每页条数
     */
    List<AiSessionSummary> listByUser(long userId, AiSessionQuery query, int offset, int limit);

    /** 与 {@link #listByUser} 完全同条件的总数，供分页字段使用。 */
    int countByUser(long userId, AiSessionQuery query);
}
