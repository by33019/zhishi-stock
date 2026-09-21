package cn.zhishi.stock.ai.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code ai_context_snapshot} 的 SQL。
 *
 * <p>只有插入与计数：读取方是 M3-08 的历史与证据接口。
 * 现在补一个 {@code findByTask} 只会有零个调用者，而"零调用者的方法"
 * 最容易在将来被误当成已实现的能力。
 */
@Mapper
public interface AiContextSnapshotMapper {

    @Insert("""
            INSERT INTO ai_context_snapshot
              (id, task_id, snapshot_no, context_type, source_object_type, source_object_id,
               source_key, data_time, data_cutoff_at, content_hash, context_data,
               is_evidence_candidate)
            VALUES
              (#{id}, #{taskId}, #{snapshotNo}, #{contextType}, #{sourceObjectType},
               #{sourceObjectId}, #{sourceKey}, #{dataTime}, #{dataCutoffAt}, #{contentHash},
               #{contextData}, #{evidenceCandidate})
            """)
    void insert(AiContextSnapshotRow row);

    @Select("SELECT COUNT(*) FROM ai_context_snapshot WHERE task_id = #{taskId}")
    int countByTask(@Param("taskId") long taskId);
}
