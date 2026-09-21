package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiContextSnapshot;
import cn.zhishi.stock.ai.domain.AiContextSnapshotStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * {@link AiContextSnapshotStore} 的 MyBatis 实现。
 *
 * <p>{@code context_data} 的 JSON 转换在**这里**完成，而不是靠全局注册的
 * {@code Map} TypeHandler：全局注册会让"哪些字段会被 JSON 序列化"变成一个隐式约定，
 * 而显式转换只有一处，且不依赖注册顺序。
 *
 * <p>写：把一个合法 JSON 字符串交给 JSON 列（MySQL 隐式转换）。
 * 读：MySQL Connector/J 把 JSON 列返回为字符串。
 * 本轮没有读取方（M3-08 才需要），但转换逻辑仍然写全——半截的读写不对称
 * 会让 M3-08 的实现者以为"库里存的是别的东西"。
 */
public class MyBatisAiContextSnapshotStore implements AiContextSnapshotStore {

    private final AiContextSnapshotMapper mapper;
    private final ObjectMapper objectMapper;
    private final LongSupplier idGenerator;
    private final Clock clock;

    public MyBatisAiContextSnapshotStore(
            AiContextSnapshotMapper mapper,
            ObjectMapper objectMapper,
            LongSupplier idGenerator,
            Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public void insertAll(long taskId, List<AiContextSnapshot> snapshots) {
        for (AiContextSnapshot snapshot : snapshots) {
            mapper.insert(new AiContextSnapshotRow(
                    idGenerator.getAsLong(),
                    taskId,
                    snapshot.snapshotNo(),
                    snapshot.contextType(),
                    snapshot.sourceObjectType(),
                    snapshot.sourceObjectId(),
                    snapshot.sourceKey(),
                    toLocalDateTime(snapshot.dataTime()),
                    toLocalDateTime(snapshot.dataCutoffAt()),
                    snapshot.contentHash(),
                    writeJson(snapshot.contextData()),
                    snapshot.isEvidenceCandidate()));
        }
    }

    @Override
    public int countByTask(long taskId) {
        return mapper.countByTask(taskId);
    }

    /** 供读取方（M3-08）复用的反序列化口径。 */
    public Map<String, Object> readContextData(AiContextSnapshotRow row) {
        if (row.contextData() == null || row.contextData().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(row.contextData(), new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "上下文快照反序列化失败：snapshotId=" + row.id(), exception);
        }
    }

    private String writeJson(Map<String, Object> content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("上下文快照序列化失败", exception);
        }
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }
}
