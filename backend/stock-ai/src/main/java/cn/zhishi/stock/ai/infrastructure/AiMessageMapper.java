package cn.zhishi.stock.ai.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code ai_message} 的 SQL。
 *
 * <p>插入**不写** {@code content_format} 与 {@code status}：表默认值分别是
 * {@code MARKDOWN} 与 {@code COMPLETED}，而本轮每一条消息都是定稿的 Markdown。
 * 把恒定值当参数传，只会让"这两个字段其实有别的取值"变成一个无法证伪的猜测。
 */
@Mapper
public interface AiMessageMapper {

    @Insert("""
            INSERT INTO ai_message
              (id, session_id, task_id, role_type, sequence_no, content, data_cutoff_at, created_at)
            VALUES
              (#{messageId}, #{sessionId}, #{taskId}, #{roleType}, #{sequenceNo}, #{content},
               #{dataCutoffAt}, #{createdAt})
            """)
    void insert(AiMessageRow row);

    /**
     * 会话内下一个消息序号。
     *
     * <p>现算而不是维护独立计数器：会话内的顺序只有一处事实来源（表里的行）。
     * 并发撞号由 {@code uk_ai_message_session_sequence} 兜底——一次任务只写两条消息，
     * 概率极低，而真撞上时唯一索引会明确报错，不会静默产生乱序。
     */
    @Select("""
            SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM ai_message WHERE session_id = #{sessionId}
            """)
    int nextSequenceNo(@Param("sessionId") long sessionId);

    @Select("SELECT COUNT(*) FROM ai_message WHERE session_id = #{sessionId}")
    int countBySession(@Param("sessionId") long sessionId);
}
