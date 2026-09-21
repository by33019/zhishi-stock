package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiTargetRole;
import cn.zhishi.stock.ai.domain.AiTargetType;

/**
 * {@code ai_task_target} 的行映射结果。
 *
 * <p>注意 {@code targetId} 是 **bigint 代理键**，不是对外标识：
 * 表就是这么定义的（V6，与 {@code stock_news_relation.target_id} 同形）。
 * 对外标识由 {@code *IdentityProvider.findByStorageIds} 还原，不在这里拼字符串。
 */
public record AiTaskTargetRow(
        long id,
        long taskId,
        AiTargetType targetType,
        long targetId,
        String targetCode,
        String targetName,
        AiTargetRole targetRole,
        int sortNo) {
}
