package cn.zhishi.stock.ai.application;

import cn.zhishi.stock.ai.domain.AiReport;
import cn.zhishi.stock.ai.domain.AiReportSection;
import cn.zhishi.stock.ai.domain.AiTaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SSE 事件的 {@code data} 载荷（契约 §13.4）。
 *
 * <h2>为什么载荷在这里拼，而不是在 worker 与中继各自拼</h2>
 * 六类事件里有五类由 worker 写、{@code snapshot} 由中继写，但它们的字段名是**同一份契约**。
 * 分两处写会出现"worker 发 {@code finalStatus}、中继发 {@code status}"这种不一致，
 * 而前端只会把不认识的那个字段当成不存在——不报错，只是少显示一个东西。
 *
 * <h2>{@code taskId} 是字符串</h2>
 * 业务 ID 全程用 Snowflake 字符串（架构约定），契约 §13.4 的示例也是
 * {@code "taskId":"19876543219999"}。这里发数字会让前端在大 ID 上丢精度，
 * 而丢精度只会在某一天突然出现，不会在开发时暴露。
 *
 * <h2>{@code sequence} 与 SSE 的 {@code id:} 是同一个值</h2>
 * 它就是 Redis Stream 消息 ID 的序号部分（见 {@code RedisAiTaskEventStream}）。
 * 两个计数器必然分叉，而分叉的表现是"重连后前端重复拼接已经收到的片段"。
 */
public final class AiTaskEventPayloads {

    /**
     * 载荷序列化器。
     *
     * <p>必须注册 {@code JavaTimeModule}：{@code snapshot} 载荷里嵌着整个
     * {@code AiTaskSummary}，它带 {@code OffsetDateTime}，而**裸的 ObjectMapper
     * 不支持 java.time 类型**——不注册的话 {@code snapshot} 会直接抛
     * "无法序列化"，也就是建连时第一条事件就发不出去。
     *
     * <p>关掉 {@code WRITE_DATES_AS_TIMESTAMPS}：时间要写成 ISO 字符串，
     * 与契约里其余接口的形状一致（数字数组形状的日期前端认不出来）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AiTaskEventPayloads() {
    }

    public static String status(long taskId, AiTaskStatus status, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("status", status.name());
        payload.put("progressStage", status.progressStage());
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String chunk(long taskId, AiReportSection section, String delta, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("section", section.name());
        payload.put("delta", delta);
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String report(long taskId, AiReport report, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("reportId", Long.toString(report.reportId()));
        payload.put("qualityStatus", report.qualityStatus());
        payload.put("isLimited", report.limited());
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String error(
            long taskId, String errorCode, String message, boolean retryable, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        payload.put("retryable", retryable);
        payload.put("sequence", sequence);
        return write(payload);
    }

    public static String done(long taskId, AiTaskStatus finalStatus, long sequence) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", Long.toString(taskId));
        payload.put("finalStatus", finalStatus.name());
        payload.put("sequence", sequence);
        return write(payload);
    }

    /**
     * 建连或重连后的当前完整状态。
     *
     * <p>{@code partialContent} 只在**确实有临时片段**时给出。空字符串与"没有片段"
     * 对前端的含义不同：前者会让前端把已经渲染的文本清空成空白。
     */
    public static String snapshot(
            AiTaskSummary task, long lastSequence, String partialContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", task);
        payload.put("lastSequence", lastSequence);
        if (partialContent != null && !partialContent.isEmpty()) {
            payload.put("partialContent", partialContent);
        }
        return write(payload);
    }

    /**
     * 从 {@code chunk} 载荷里读回片段。
     *
     * <p>中继要用它把"已经产生的临时文本"拼出来（重连时的 {@code partialContent}）。
     * 读的口径与 {@link #chunk} 的写出口径**放在同一处**：分两处写就会出现
     * "写的时候叫 {@code delta}、读的时候找 {@code text}"这种不一致，
     * 而它不会报错，只会让重连后前端拿到一段空白。
     *
     * @return 载荷不是 chunk 形状时返回 {@code Optional.empty()}（不抛异常：
     *         中继只是转发者，遇到不认识的载荷应当跳过而不是让整条流断掉）
     */
    public static Optional<ChunkView> readChunk(String dataJson) {
        try {
            JsonNode node = MAPPER.readTree(dataJson);
            String section = node.path("section").asText(null);
            String delta = node.path("delta").asText(null);
            if (section == null || delta == null) {
                return Optional.empty();
            }
            AiReportSection parsed = AiReportSection.valueOf(section);
            return Optional.of(new ChunkView(parsed, delta));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** {@code chunk} 载荷里与中继有关的两个字段。 */
    public record ChunkView(AiReportSection section, String delta) {
    }

    private static String write(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            // 载荷全部是字符串与数字，序列化失败意味着代码写错了（比如塞进了不可序列化的对象）。
            throw new IllegalStateException("AI 事件载荷无法序列化", exception);
        }
    }
}
