package cn.zhishi.stock.ai.domain;

import java.util.Optional;

/**
 * 报告反馈仓储（{@code ai_feedback}）。
 *
 * <h2>写路径只有 upsert 一个动词</h2>
 * 契约 §HIS-08 要的是"创建**或替换**"，对应库里的
 * {@code uk_ai_feedback_report_user} 唯一索引。因此不提供 {@code insert}：
 * 一旦提供，某个调用方就会用它，而同一个人对同一份报告再评一次时
 * 要么撞唯一索引报错、要么留下两行——后者会让反馈统计悄悄偏高。
 *
 * <h2>{@link #upsert} 之后必须回读</h2>
 * 撞唯一索引时数据库保留**原有行的 id 与 created_at**，本次传入的自增 id 被丢弃。
 * 若直接把入参当作结果返回，调用方拿到的 {@code feedbackId} 会指向不存在的行。
 * 因此用例层要重新 {@link #find} 一次再构造响应。
 */
public interface AiFeedbackStore {

    /** 插入或按 {@code (reportId, userId)} 覆盖已有行。 */
    void upsert(AiFeedback feedback);

    /** 某人对某份报告的反馈。 */
    Optional<AiFeedback> find(long reportId, long userId);

    /**
     * 删除某人对某份报告的反馈。
     *
     * @return 是否真的删掉了一行；{@code false} 表示本来就没有
     */
    boolean delete(long reportId, long userId);
}
