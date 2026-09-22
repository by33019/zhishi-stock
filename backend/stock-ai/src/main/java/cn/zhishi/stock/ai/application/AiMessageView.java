package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiMessage;
import cn.zhishi.stock.ai.domain.AiMessageRole;
import java.time.OffsetDateTime;

/**
 * 会话消息（契约 §HIS-05）。
 *
 * <h2>契约里有两个字段本实现给不出，且刻意不给</h2>
 * 契约 §HIS-05 的返回列含 {@code contentFormat} 与 {@code status}，而
 * {@code ai_message} 表**没有这两列**（见 V6 建表：{@code id / session_id / task_id /
 * role_type / sequence_no / content / data_cutoff_at / created_at}）。
 *
 * <p>可以"推"出它们：{@code ASSISTANT} 的 {@code content} 由
 * {@code AiReportText.renderMarkdown} 写入、{@code USER} 的是原始提问，于是能按角色
 * 编出 {@code MARKDOWN} / {@code TEXT}。**但不这么做**——那条规则的定义在写入侧
 * （执行器），在读取侧再推一遍就是同一事实的第二处定义。写入侧将来改格式时，
 * 读取侧会静默说错，而没有任何东西会报错。
 *
 * <p>因此这两个字段**不出现**在响应里（不是返回 {@code null}）。前端的处置与
 * 其它"无数据源"的字段一致：不渲染。补齐它们需要一次迁移（给 {@code ai_message}
 * 加列）或契约调整，已记入已知问题。
 *
 * @param roleType       {@code USER} / {@code ASSISTANT}；{@code SYSTEM} 已在查询层排除
 * @param sequenceNo     会话内顺序，升序
 * @param dataCutoffAt   该条回答所依据的数据截止时刻；用户提问没有这个语义，为 {@code null}
 */
public record AiMessageView(
        String messageId,
        String taskId,
        AiMessageRole roleType,
        int sequenceNo,
        String content,
        OffsetDateTime dataCutoffAt,
        OffsetDateTime createdAt) {

    public static AiMessageView from(AiMessage message) {
        return new AiMessageView(
                Long.toString(message.messageId()),
                message.taskId() == null ? null : Long.toString(message.taskId()),
                message.roleType(),
                message.sequenceNo(),
                message.content(),
                message.dataCutoffAt(),
                message.createdAt());
    }
}
