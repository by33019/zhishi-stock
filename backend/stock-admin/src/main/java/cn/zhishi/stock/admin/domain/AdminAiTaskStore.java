package cn.zhishi.stock.admin.domain;

import java.util.List;
import java.util.Optional;

/**
 * 后台 AI 任务的读取端口（契约 §19 ADM-AI-02 / ADM-AI-03）。
 *
 * <h2>为什么后台自带端口而不扩 {@code ai} 域的 {@code AiTaskStore}</h2>
 * AI 域的端口是为任务编排设计的（按用户、按恢复扫描取任务），
 * 后台要的是跨用户的元数据分页与统计聚合——两种形状没有交集。
 * 与 {@code AdminUserStore} 之于 {@code sys_user} 同一条路：
 * 表是共享的，端口按读写方的用例切。
 *
 * <p>与 {@code AiTaskStore.save} 的乐观锁写不同，这里全是只读——
 * 唯一的写路径（取消）复用 AI 域的 {@code AiTaskStore.requestCancel}，
 * 原子置 {@code cancel_requested} 的语义不复制第二份。
 */
public interface AdminAiTaskStore {

    List<AdminAiTaskSummary> page(AdminAiTaskQuery query);

    long count(AdminAiTaskQuery query);

    Optional<AdminAiTaskDetail> find(long taskId);
}
