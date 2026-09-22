package cn.zhishi.stock.ai.infrastructure;

import java.time.LocalDateTime;
import java.util.List;
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

    /**
     * 列表投影：{@code LEFT JOIN ai_task} 取最近任务状态。
     *
     * <p>用 join 而不是逐行去查任务：一页 20 条就是 20 次查询（N+1），
     * 而列表页是刷新最频繁的页面。{@code LEFT JOIN} 保证"从未跑过任务"的会话仍在结果里。
     */
    String LIST_COLUMNS = """
            s.id               AS sessionId,
            s.user_id          AS userId,
            s.scene            AS scene,
            s.title            AS title,
            s.status           AS status,
            s.is_favorite      AS favorite,
            s.last_task_id     AS lastTaskId,
            t.status           AS lastTaskStatus,
            s.last_activity_at AS lastActivityAt,
            s.created_at       AS createdAt,
            s.version          AS version
            """;

    /**
     * 列表筛选条件。
     *
     * <p>写成静态 SQL 的"空值即不过滤"，而不是 MyBatis 的 {@code <script>/<if>}：
     * 动态标签要处理 XML 转义，而这里只有五个可选条件，静态写法读起来更直接，
     * 也不必把注释和 SQL 拼成两种语言。每个参数都显式声明 {@code jdbcType}
     * —— 可空参数不声明时，部分驱动会报"必须指定 JdbcType"。
     *
     * <p>{@code status <> 'DELETED'} 是硬条件：软删除的会话对普通列表**不可见**（契约 §HIS-02）。
     */
    String LIST_WHERE = """
            FROM ai_session s
            LEFT JOIN ai_task t ON t.id = s.last_task_id
            WHERE s.user_id = #{userId}
              AND s.status <> 'DELETED'
              AND (#{scene,jdbcType=VARCHAR} IS NULL OR s.scene = #{scene,jdbcType=VARCHAR})
              AND (#{keyword,jdbcType=VARCHAR} IS NULL
                   OR s.title LIKE CONCAT('%', #{keyword,jdbcType=VARCHAR}, '%'))
              AND (#{favorite,jdbcType=BOOLEAN} IS NULL
                   OR s.is_favorite = #{favorite,jdbcType=BOOLEAN})
              AND (#{startAt,jdbcType=TIMESTAMP} IS NULL
                   OR s.last_activity_at >= #{startAt,jdbcType=TIMESTAMP})
              AND (#{endAt,jdbcType=TIMESTAMP} IS NULL
                   OR s.last_activity_at <= #{endAt,jdbcType=TIMESTAMP})
            """;

    /** 按最后活动时间倒序（契约 §HIS-01）。id 作兜底键：同一时刻的会话顺序不能靠底层遍历顺序。 */
    @Select("SELECT " + LIST_COLUMNS + LIST_WHERE
            + " ORDER BY s.last_activity_at DESC, s.id DESC LIMIT #{limit} OFFSET #{offset}")
    List<AiSessionSummaryRow> listByUser(
            @Param("userId") long userId,
            @Param("scene") String scene,
            @Param("keyword") String keyword,
            @Param("favorite") Boolean favorite,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("SELECT COUNT(*) " + LIST_WHERE)
    int countByUser(
            @Param("userId") long userId,
            @Param("scene") String scene,
            @Param("keyword") String keyword,
            @Param("favorite") Boolean favorite,
            @Param("startAt") LocalDateTime startAt,
            @Param("endAt") LocalDateTime endAt);

    /**
     * 改名 / 收藏（契约 §HIS-03）。
     *
     * <p>{@code WHERE version = #{version}} 是乐观锁：影响 0 行表示"这份会话在你读取之后
     * 被改过了"，由用例层转成 409。
     *
     * <p>{@code status <> 'DELETED'} 让已软删的会话不可再改。少了它，一次删除与一次改名
     * 并发时后者会"成功"，把已删会话又改出用户可见的内容。
     *
     * @return 影响行数；0 表示版本冲突或会话已删除
     */
    @Update("""
            UPDATE ai_session
               SET title = #{title},
                   is_favorite = #{favorite},
                   version = version + 1
             WHERE id = #{sessionId} AND version = #{version} AND status <> 'DELETED'
            """)
    int update(
            @Param("sessionId") long sessionId,
            @Param("version") int version,
            @Param("title") String title,
            @Param("favorite") boolean favorite);

    /**
     * 软删除（契约 §HIS-04）。
     *
     * <p>三个字段必须一起写：{@code ck_ai_session_delete_state} 要求
     * {@code status='DELETED' ⇔ deleted_at 与 purge_after 都非空}。只改 status
     * 会被数据库拒绝，而那条约束错误读起来像"字段没填"，不像"删除状态不自洽"。
     *
     * @return 影响行数；0 表示版本冲突或已经是删除态
     */
    @Update("""
            UPDATE ai_session
               SET status = 'DELETED',
                   deleted_at = #{deletedAt},
                   purge_after = #{purgeAfter},
                   version = version + 1
             WHERE id = #{sessionId} AND version = #{version} AND status <> 'DELETED'
            """)
    int softDelete(
            @Param("sessionId") long sessionId,
            @Param("version") int version,
            @Param("deletedAt") LocalDateTime deletedAt,
            @Param("purgeAfter") LocalDateTime purgeAfter);

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
