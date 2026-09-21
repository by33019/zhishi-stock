package cn.zhishi.stock.ai.domain;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * AI 会话仓储（{@code ai_session}）。
 *
 * <p>本轮只用到"建、取、更新最后任务与活动时间"三件事——HIS-01~HIS-06 六个历史接口
 * 属 M3-08，届时才需要分页、筛选与 {@code If-Match} 版本冲突。
 * 提前长出那些方法会让本轮的接口形状被一个还没定的需求牵着走。
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
}
