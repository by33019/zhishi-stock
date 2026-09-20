package cn.zhishi.stock.ai.domain;

/**
 * 流式输出的一个片段（契约 §13.4 的 SSE {@code chunk} 事件）。
 *
 * @param section  所属章节
 * @param delta    增量文本
 * @param sequence 片段序号，从 1 开始连续递增；SSE 的 {@code id} 字段用它支持断线补发
 */
public record LlmChunk(AiReportSection section, String delta, int sequence) {

    public LlmChunk {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence 从 1 开始：" + sequence);
        }
    }
}
