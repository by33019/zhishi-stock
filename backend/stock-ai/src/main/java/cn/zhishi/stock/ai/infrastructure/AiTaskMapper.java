package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiTaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code ai_task} 与 {@code ai_task_target} 的 SQL。
 *
 * <h2>两条写路径的区别写在 SQL 里，而不是靠调用方自觉</h2>
 * <ul>
 *   <li>{@link #updateWithVersion} 带 {@code AND version = #{version}}：读-改-写的乐观锁。
 *   <li>{@link #claimForExecution} 与 {@link #requestCancel} 是**原子条件更新**：
 *       "谁先到谁赢"的语义必须由 SQL 保证，拆成先读再写在并发下会双赢。
 * </ul>
 *
 * <h2>插入不吞唯一索引冲突</h2>
 * {@code uk_ai_task_request_id} 撞索引意味着"同一个幂等键并发创建了两次"，
 * 由用例层决定返回哪一个（契约 §3.7 要的是不重复消耗额度，不是必须失败）。
 * 在这里 {@code INSERT IGNORE} 会让"跳过"与"写入失败"变得不可区分。
 *
 * <h2>{@code report_id} 不在这张表里，也不在这份 SQL 里</h2>
 * 任务的最终报告由 {@code ai_report.task_id} 指向任务（{@code uk_ai_report_task} 保证唯一），
 * 而不是反过来。早先这里的列清单与 {@code UPDATE} 里都写了 {@code report_id}，
 * 于是所有走真库的路径都报 {@code Unknown column 'report_id'}——
 * 内存桩看不到表结构，所以单测全绿。加一列能"修好"它，但会让
 * "任务的报告是哪一个"有两处答案，因此选择删掉引用而不是补列。
 */
@Mapper
public interface AiTaskMapper {

    String COLUMNS = """
            id                AS taskId,
            request_id        AS requestId,
            session_id        AS sessionId,
            user_id           AS userId,
            retry_of_task_id  AS retryOfTaskId,
            scene             AS scene,
            question          AS question,
            analysis_start_at AS analysisStartAt,
            analysis_end_at   AS analysisEndAt,
            status            AS status,
            attempt_no        AS attemptNo,
            max_attempts      AS maxAttempts,
            cancel_requested  AS cancelRequested,
            provider_code     AS providerCode,
            model_code        AS modelCode,
            trace_id          AS traceId,
            created_at        AS createdAt,
            queued_at         AS queuedAt,
            started_at        AS startedAt,
            first_chunk_at    AS firstChunkAt,
            validating_at     AS validatingAt,
            heartbeat_at      AS heartbeatAt,
            deadline_at       AS deadlineAt,
            completed_at      AS completedAt,
            error_category    AS errorCategory,
            error_code        AS errorCode,
            error_message     AS errorMessage,
            version           AS version
            """;

    String SELECT_TASK = "SELECT " + COLUMNS + " FROM ai_task";

    String TARGET_COLUMNS = """
            id          AS id,
            task_id     AS taskId,
            target_type AS targetType,
            target_id   AS targetId,
            target_code AS targetCode,
            target_name AS targetName,
            target_role AS targetRole,
            sort_no     AS sortNo
            """;

    String STATUS_IN = """
            <foreach item="status" collection="statuses" open="(" separator="," close=")">#{status}</foreach>
            """;

    @Select(SELECT_TASK + " WHERE id = #{taskId}")
    AiTaskRow find(@Param("taskId") long taskId);

    @Select(SELECT_TASK + " WHERE request_id = #{requestId}")
    AiTaskRow findByRequestId(@Param("requestId") String requestId);

    @Select("SELECT " + TARGET_COLUMNS
            + " FROM ai_task_target WHERE task_id = #{taskId} ORDER BY sort_no, id")
    List<AiTaskTargetRow> findTargets(@Param("taskId") long taskId);

    @Insert("""
            INSERT INTO ai_task
              (id, request_id, session_id, user_id, retry_of_task_id, scene, question,
               analysis_start_at, analysis_end_at, status, attempt_no, max_attempts,
               cancel_requested, provider_code, model_code, trace_id, created_at,
               queued_at, deadline_at, version)
            VALUES
              (#{taskId}, #{requestId}, #{sessionId}, #{userId}, #{retryOfTaskId}, #{scene},
               #{question}, #{analysisStartAt}, #{analysisEndAt}, #{status}, #{attemptNo},
               #{maxAttempts}, #{cancelRequested}, #{providerCode}, #{modelCode}, #{traceId},
               #{createdAt}, #{queuedAt}, #{deadlineAt}, 0)
            """)
    void insert(AiTaskRow row);

    @Insert("""
            INSERT INTO ai_task_target
              (id, task_id, target_type, target_id, target_code, target_name, target_role, sort_no)
            VALUES
              (#{id}, #{taskId}, #{targetType}, #{targetId}, #{targetCode}, #{targetName},
               #{targetRole}, #{sortNo})
            """)
    void insertTarget(AiTaskTargetRow row);

    /**
     * 乐观锁写回全部可变字段。
     *
     * <p>{@code WHERE ... AND version = #{version}} 命中 0 行即表示"我读到的版本已经过期"。
     * 更新成功后 {@code version + 1}，因此调用方手里的旧 record 会立刻失效——
     * 这是刻意的：继续用旧 record 写第二次本来就应该失败。
     *
     * <p>{@code provider_code} / {@code model_code} / {@code trace_id} / 场景 / 区间 / 问题
     * 刻意不在更新列里：它们在创建时固化，之后不变。写进去只会让"某个字段其实不会被改"
     * 变成一个需要读 SQL 才能确认的事。
     */
    @Update("""
            UPDATE ai_task SET
              status = #{status},
              attempt_no = #{attemptNo},
              cancel_requested = #{cancelRequested},
              queued_at = #{queuedAt},
              started_at = #{startedAt},
              first_chunk_at = #{firstChunkAt},
              validating_at = #{validatingAt},
              heartbeat_at = #{heartbeatAt},
              completed_at = #{completedAt},
              error_category = #{errorCategory},
              error_code = #{errorCode},
              error_message = #{errorMessage},
              version = version + 1
            WHERE id = #{taskId} AND version = #{version}
            """)
    int updateWithVersion(AiTaskRow row);

    /**
     * 抢占执行权。
     *
     * <p>{@code cancel_requested = 0} 是条件的一部分：一个已被要求取消的任务
     * 不应该再被取走执行——那会消耗一次真实的模型调用，而结果注定被丢弃。
     *
     * <p>同时清掉上一次的错误摘要：新一次尝试不该带着旧失败信息（否则
     * {@code AiTaskSummary} 会在"运行中"的任务上显示一条失败提示）。
     */
    @Update("""
            UPDATE ai_task SET
              status = 'PREPARING',
              attempt_no = attempt_no + 1,
              started_at = #{at},
              heartbeat_at = #{at},
              error_category = NULL,
              error_code = NULL,
              error_message = NULL,
              version = version + 1
            WHERE id = #{taskId}
              AND status IN ('CREATED', 'QUEUED')
              AND cancel_requested = 0
              AND attempt_no < max_attempts
            """)
    int claimForExecution(@Param("taskId") long taskId, @Param("at") LocalDateTime at);

    /** 置取消意图。不改 {@code status}——状态是事实，取消是意图，两者分开存。 */
    @Update("""
            UPDATE ai_task SET cancel_requested = 1, version = version + 1
            WHERE id = #{taskId} AND cancel_requested = 0
            """)
    int requestCancel(@Param("taskId") long taskId);

    @Select("<script>" + SELECT_TASK
            + """
             WHERE user_id = #{userId} AND status IN
            """ + STATUS_IN
            + " ORDER BY created_at DESC, id DESC</script>")
    List<AiTaskRow> findByUserAndStatuses(
            @Param("userId") long userId, @Param("statuses") List<AiTaskStatus> statuses);

    @Select("<script>SELECT COUNT(*) FROM ai_task"
            + """
             WHERE user_id = #{userId} AND status IN
            """ + STATUS_IN + "</script>")
    int countByUserAndStatuses(
            @Param("userId") long userId, @Param("statuses") List<AiTaskStatus> statuses);

    @Select("SELECT COUNT(*) FROM ai_task WHERE user_id = #{userId} AND created_at >= #{from}")
    int countCreatedSince(@Param("userId") long userId, @Param("from") LocalDateTime from);

    /**
     * 恢复扫描的候选集。
     *
     * <p>三类合一：队列消息丢了的 {@code QUEUED}、被要求取消的 {@code QUEUED}、
     * 以及心跳过期的非终态任务。合成一条查询是为了让扫描器在一个批次里
     * 看到全部需要处理的任务——分三条查询会让"先处理哪一类"变成一个随实现漂移的事实。
     */
    @Select(SELECT_TASK + """
             WHERE status IN ('QUEUED', 'PREPARING', 'RUNNING', 'VALIDATING')
               AND (
                     (status = 'QUEUED'
                      AND (cancel_requested = 1 OR created_at < #{queuedBefore}))
                  OR (status <> 'QUEUED'
                      AND (heartbeat_at IS NULL OR heartbeat_at < #{heartbeatBefore}))
               )
             ORDER BY created_at, id
             LIMIT #{limit}
            """)
    List<AiTaskRow> findRecoverable(
            @Param("queuedBefore") LocalDateTime queuedBefore,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("limit") int limit);
}
