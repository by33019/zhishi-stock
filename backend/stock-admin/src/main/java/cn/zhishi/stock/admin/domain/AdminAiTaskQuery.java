package cn.zhishi.stock.admin.domain;

import cn.zhishi.stock.ai.domain.AiTaskStatus;
import cn.zhishi.stock.ai.domain.LlmErrorCategory;
import java.time.OffsetDateTime;

/**
 * 后台 AI 任务的查询条件（契约 §19 ADM-AI-02）。
 *
 * <p>时间范围与操作日志同一套口径（缺省 7 天、上限 90 天），由
 * {@code AdminAiService} 用注入的 {@code Clock} 解析后经 {@link #withRange} 传回；
 * 范围筛选的是任务的 {@code created_at}。
 */
public record AdminAiTaskQuery(
        Long taskId,
        Long userId,
        String scene,
        AiTaskStatus status,
        String providerCode,
        LlmErrorCategory errorCategory,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        int page,
        int size) {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_RANGE_DAYS = 7;
    public static final int MAX_RANGE_DAYS = 90;

    public AdminAiTaskQuery {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
    }

    public int offset() {
        return (page - 1) * size;
    }

    /** 用已解析的时间范围换一份新条件；其余字段原样保留。 */
    public AdminAiTaskQuery withRange(OffsetDateTime start, OffsetDateTime end) {
        return new AdminAiTaskQuery(
                taskId, userId, scene, status, providerCode, errorCategory,
                start, end, page, size);
    }
}
