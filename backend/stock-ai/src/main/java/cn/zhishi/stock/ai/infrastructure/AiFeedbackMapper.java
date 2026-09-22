package cn.zhishi.stock.ai.infrastructure;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiFeedbackMapper {

    String COLUMNS = """
            id            AS feedbackId,
            report_id     AS reportId,
            user_id       AS userId,
            feedback_type AS feedbackType,
            reason_code   AS reasonCode,
            detail        AS detail,
            created_at    AS createdAt,
            updated_at    AS updatedAt
            """;

    /**
     * 插入或覆盖。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查再决定插还是改"：
     * 后者在并发下会两个请求都判为"不存在"，然后其中一个撞唯一索引报错——
     * 而"同一个人连点两次评价"完全正常，不该看到 500。
     *
     * <p>刻意**不更新** {@code created_at}：它记录的是"第一次评价的时间"，
     * 改评价不该把它抹掉（契约 §HIS-08 的响应里也只有 {@code updatedAt}，
     * 但内部聚合将来要按首次评价时间看趋势）。
     */
    @Insert("""
            INSERT INTO ai_feedback
              (id, report_id, user_id, feedback_type, reason_code, detail)
            VALUES
              (#{feedbackId}, #{reportId}, #{userId}, #{feedbackType}, #{reasonCode}, #{detail})
            ON DUPLICATE KEY UPDATE
              feedback_type = VALUES(feedback_type),
              reason_code   = VALUES(reason_code),
              detail        = VALUES(detail)
            """)
    void upsert(AiFeedbackRow row);

    @Select("SELECT " + COLUMNS + " FROM ai_feedback"
            + " WHERE report_id = #{reportId} AND user_id = #{userId}")
    AiFeedbackRow find(@Param("reportId") long reportId, @Param("userId") long userId);

    @Delete("DELETE FROM ai_feedback WHERE report_id = #{reportId} AND user_id = #{userId}")
    int delete(@Param("reportId") long reportId, @Param("userId") long userId);
}
