package cn.zhishi.stock.ai.infrastructure;

import java.util.List;
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

    String COLUMNS = """
            id            AS messageId,
            session_id    AS sessionId,
            task_id       AS taskId,
            role_type     AS roleType,
            sequence_no   AS sequenceNo,
            content       AS content,
            data_cutoff_at AS dataCutoffAt,
            created_at    AS createdAt
            """;

    /**
     * 可见消息的筛选条件。
     *
     * <p>**硬条件 {@code role_type <> 'SYSTEM'}**：契约 §HIS-05 明写
     * "不返回 {@code SYSTEM} 内部 Prompt，系统消息仅返回可公开状态说明"。
     *
     * <p>在 SQL 里排除而不是取回再在应用层过滤：后者会让"这一页取 20 条、
     * 过滤后返回 17 条"发生，而分页字段仍是按 20 算的——总数与页内容不一致，
     * 且不会报错，只是最后几页看起来少了东西。计数也必须用同一条 WHERE，
     * 两处各写一份必然漂移。
     */
    String VISIBLE_WHERE = """
            FROM ai_message
            WHERE session_id = #{sessionId}
              AND role_type <> 'SYSTEM'
            """;

    @Select("SELECT " + COLUMNS + VISIBLE_WHERE
            + " ORDER BY sequence_no ASC LIMIT #{limit} OFFSET #{offset}")
    List<AiMessageRow> listVisible(
            @Param("sessionId") long sessionId,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) " + VISIBLE_WHERE)
    int countVisible(@Param("sessionId") long sessionId);

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
