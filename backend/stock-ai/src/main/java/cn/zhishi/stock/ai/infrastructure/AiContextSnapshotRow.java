package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiContextType;
import java.time.LocalDateTime;

/**
 * {@code ai_context_snapshot} 的行映射结果。
 *
 * <h2>{@code contextData} 是 JSON 文本，不是 {@code Map}</h2>
 * 刻意不做 {@code Map} ↔ JSON 的 MyBatis TypeHandler：那需要全局注册一个
 * {@code Map} 类型的处理器，于是"哪些字段会被 JSON 序列化"变成一个隐式的全局约定。
 * 这里在存储层显式转换（写时 {@code writeValueAsString}，读时 {@code readValue}），
 * 转换点只有一处，且不依赖任何注册顺序。
 *
 * <p>写入时把一个合法 JSON 字符串交给 JSON 列，MySQL 会隐式转换；
 * 读取时 MySQL Connector/J 把 JSON 列返回为字符串。两端都不需要额外配置。
 */
public record AiContextSnapshotRow(
        long id,
        long taskId,
        int snapshotNo,
        AiContextType contextType,
        String sourceObjectType,
        Long sourceObjectId,
        String sourceKey,
        LocalDateTime dataTime,
        LocalDateTime dataCutoffAt,
        String contentHash,
        String contextData,
        boolean evidenceCandidate) {
}
