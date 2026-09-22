package cn.zhishi.stock.ai.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiEvidenceMapper {

    String COLUMNS = """
            id                   AS evidenceId,
            report_id            AS reportId,
            evidence_no          AS evidenceNo,
            context_snapshot_id  AS contextSnapshotId,
            evidence_type        AS evidenceType,
            source_object_type   AS sourceObjectType,
            source_object_id     AS sourceObjectId,
            source_title         AS sourceTitle,
            source_url           AS sourceUrl,
            evidence_summary     AS evidenceSummary,
            source_published_at  AS sourcePublishedAt,
            data_time            AS dataTime,
            access_status        AS accessStatus,
            content_hash         AS contentHash,
            created_at           AS createdAt
            """;

    /**
     * 整批写入。
     *
     * <p>用多值 {@code VALUES} 而不是逐条循环：一份报告的证据是一次定稿的全集
     * （个位数行），逐条往返只放大延迟，没有任何收益。
     * 幂等由 {@code uk_ai_evidence_report_no} 兜底——重复写入同一报告会撞唯一索引报错，
     * 而不是静默翻倍，这正是想要的（晚到的写入意味着定稿流程出了别的问题）。
     */
    @Insert("""
            <script>
            INSERT INTO ai_evidence
              (id, report_id, evidence_no, context_snapshot_id, evidence_type,
               source_object_type, source_object_id, source_title, source_url,
               evidence_summary, source_published_at, data_time, access_status,
               content_hash, created_at)
            VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{row.evidenceId}, #{row.reportId}, #{row.evidenceNo}, #{row.contextSnapshotId},
               #{row.evidenceType}, #{row.sourceObjectType}, #{row.sourceObjectId},
               #{row.sourceTitle}, #{row.sourceUrl}, #{row.evidenceSummary},
               #{row.sourcePublishedAt}, #{row.dataTime}, #{row.accessStatus},
               #{row.contentHash}, #{row.createdAt})
            </foreach>
            </script>
            """)
    void insertAll(@Param("rows") List<AiEvidenceRow> rows);

    @Select("SELECT " + COLUMNS + " FROM ai_evidence"
            + " WHERE report_id = #{reportId} ORDER BY evidence_no ASC")
    List<AiEvidenceRow> listByReport(@Param("reportId") long reportId);
}
