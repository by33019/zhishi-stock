package cn.zhishi.stock.ai.domain;

import java.time.Duration;
import java.util.List;

/**
 * 一次 LLM 调用的完整结果。
 *
 * <p>{@code chunks} 保留全部片段：M3-07 的 SSE 中继是"边收边推"，
 * 但落库与校验需要完整文本，因此调用方拿到的是片段列表而不是"最后一段"。
 *
 * @param providerRequestId 供应商侧请求 ID，用于对账；模拟实现给出确定性值
 * @param modelCode         实际使用的模型（可能与请求不同，如供应商降级）
 * @param chunks            全部片段，顺序与产出顺序一致
 * @param usage             token 用量
 * @param firstChunkLatency 首段延迟
 * @param totalLatency      总延迟
 */
public record LlmCompletion(
        String providerRequestId,
        String modelCode,
        List<LlmChunk> chunks,
        LlmUsage usage,
        Duration firstChunkLatency,
        Duration totalLatency) {

    public LlmCompletion {
        chunks = List.copyOf(chunks);
    }

    /** 按章节拼接出的完整文本。章节顺序由 {@link AiReportSection#inOrder()} 决定。 */
    public String textOf(AiReportSection section) {
        StringBuilder builder = new StringBuilder();
        for (LlmChunk chunk : chunks) {
            if (chunk.section() == section) {
                builder.append(chunk.delta());
            }
        }
        return builder.toString();
    }

    /** 本次产出涉及的章节（按首次出现顺序去重）。 */
    public List<AiReportSection> sections() {
        return chunks.stream().map(LlmChunk::section).distinct().toList();
    }
}
