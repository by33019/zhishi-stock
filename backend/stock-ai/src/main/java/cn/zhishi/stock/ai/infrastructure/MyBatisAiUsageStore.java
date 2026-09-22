package cn.zhishi.stock.ai.infrastructure;

import cn.zhishi.stock.ai.domain.AiUsage;
import cn.zhishi.stock.ai.domain.AiUsageStore;
import cn.zhishi.stock.ai.domain.LlmUsage;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public class MyBatisAiUsageStore implements AiUsageStore {

    private final AiUsageMapper mapper;
    private final Clock clock;

    public MyBatisAiUsageStore(AiUsageMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void insert(AiUsage usage) {
        mapper.insert(toRow(usage));
    }

    @Override
    public List<AiUsage> listByTask(long taskId) {
        return mapper.listByTask(taskId).stream().map(this::toUsage).toList();
    }

    private AiUsageRow toRow(AiUsage usage) {
        LlmUsage tokens = usage.usage();
        return new AiUsageRow(
                usage.usageId(),
                usage.taskId(),
                usage.attemptNo(),
                usage.userId(),
                usage.providerCode(),
                usage.modelCode(),
                usage.providerRequestId(),
                tokens.promptTokens(),
                tokens.completionTokens(),
                tokens.cachedTokens(),
                tokens.totalTokens(),
                usage.estimatedCost(),
                usage.currencyCode(),
                usage.firstChunkLatencyMs(),
                usage.totalLatencyMs(),
                usage.resultStatus(),
                usage.errorCategory(),
                toLocalDateTime(usage.callStartedAt()),
                toLocalDateTime(usage.callCompletedAt()),
                toLocalDateTime(usage.createdAt()));
    }

    private AiUsage toUsage(AiUsageRow row) {
        return new AiUsage(
                row.usageId(),
                row.taskId(),
                row.attemptNo(),
                row.userId(),
                row.providerCode(),
                row.modelCode(),
                row.providerRequestId(),
                new LlmUsage(
                        row.promptTokens(),
                        row.completionTokens(),
                        row.cachedTokens(),
                        row.totalTokens()),
                row.estimatedCost(),
                row.currencyCode(),
                row.firstChunkLatencyMs(),
                row.totalLatencyMs(),
                row.resultStatus(),
                row.errorCategory(),
                toOffsetDateTime(row.callStartedAt()),
                toOffsetDateTime(row.callCompletedAt()),
                toOffsetDateTime(row.createdAt()));
    }

    private LocalDateTime toLocalDateTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toOffsetDateTime();
    }
}
