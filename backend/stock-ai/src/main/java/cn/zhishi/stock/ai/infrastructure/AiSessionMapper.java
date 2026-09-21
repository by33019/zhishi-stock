package cn.zhishi.stock.ai.infrastructure;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** {@code ai_session} 的 SQL。 */
@Mapper
public interface AiSessionMapper {

    String COLUMNS = """
            id               AS sessionId,
            user_id          AS userId,
            scene            AS scene,
            title            AS title,
            status           AS status,
            is_favorite      AS favorite,
            last_task_id     AS lastTaskId,
            last_activity_at AS lastActivityAt,
            version          AS version,
            created_at       AS createdAt
            """;

    @Select("SELECT " + COLUMNS + " FROM ai_session WHERE id = #{sessionId}")
    AiSessionRow find(@Param("sessionId") long sessionId);

    @Insert("""
            INSERT INTO ai_session
              (id, user_id, scene, title, status, is_favorite, last_task_id,
               last_activity_at, version, created_at)
            VALUES
              (#{sessionId}, #{userId}, #{scene}, #{title}, #{status}, #{favorite},
               #{lastTaskId}, #{lastActivityAt}, 0, #{createdAt})
            """)
    void insert(AiSessionRow row);

    /**
     * 更新最近任务与活动时间。
     *
     * <p>不做版本校验：改的是派生事实（"最后一次活动是什么时候"），不是用户可见内容。
     * 并发下后写的赢没有危害，而引入 {@code If-Match} 只会让创建任务多一个失败点。
     */
    @Update("""
            UPDATE ai_session
               SET last_task_id = #{lastTaskId},
                   last_activity_at = #{at},
                   version = version + 1
             WHERE id = #{sessionId}
            """)
    int touch(
            @Param("sessionId") long sessionId,
            @Param("lastTaskId") long lastTaskId,
            @Param("at") LocalDateTime at);
}
